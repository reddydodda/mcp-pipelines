/**
 * Deploy OpenStack compute node
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [https://10.10.10.1:8000].
 *   TARGET_SERVERS             Salt compound target to match nodes to be updated [*, G@osfamily:debian].
 *   BATCH_SIZE                 Use batching for large amount of target nodes
 *   REBOOT_NODE                Reboot Node after Network state applied [ only needed for new nodes ]
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()
def debian = new com.mirantis.mk.Debian()

def pepperEnv = "pepperEnv"
def minions
def result
def command
def commandKwargs

def maasNodes = []

def reboot_node = (env.getProperty('REBOOT_NODE') ?: true).toBoolean()

def batch_size = ''
if (common.validInputParam('BATCH_SIZE')) {
    batch_size = "${BATCH_SIZE}"
}

timeout(time: 12, unit: 'HOURS') {
    node() {
        try {

            stage('Setup virtualenv for Pepper') {
                python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            }

            //Check MAAS Node
            stage('Verify MAAS Pillar ') {

            maasNodes = salt.getMinions(pepperEnv, 'I@maas:region')

            if (maasNodes.isEmpty()) {
                common.errorMsg("No MaaS Pillar was found. You can ignore this if it's expected. Otherwise you should fix you pillar. Check: https://docs.mirantis.com/mcp/latest/mcp-operations-guide/backup-restore/maas-postgresql/backupninja-postgresql-restore.html")
                }
            }

            // Check if machine is already deployed from MAAS status and skip the pipeline for MAAS states
            // if deployed no need to reboot the node at linux state.

            //Commision the node using MAAS process state:
            stage('MAAS Node Commision') {
                //
                salt.runSaltProcessStep(pepperEnv, 'I@maas:region', 'cmd.shell', ["salt-call maas.process_machines"], batch_size, true )
                sleep 10
                salt.runSaltProcessStep(pepperEnv, 'I@maas:region', 'state.apply', ["maas.machines.wait_for_ready"], batch_size, true)
                sleep 15
            }

            //Enforce Interface configuration defined in the model for server:
            stage('MAAS Assign IP') {
                //
                salt.runSaltProcessStep(pepperEnv, 'I@maas:region', 'cmd.shell', ["salt-call maas.process_assign_machines_ip"], batch_size, true )
            }

            //Enforce Disk custom configuration defined in the model for server:
            stage('MAAS Storage') {
                //
                salt.runSaltProcessStep(pepperEnv, 'I@maas:region', 'state.apply', ['maas.machines.storage'], batch_size, true)
            }

            //MAAS OS Deploy on server:
            stage('MAAS Deploy Machine') {
                //
                salt.runSaltProcessStep(pepperEnv, 'I@maas:region', 'cmd.shell', ["salt-call maas.deploy_machines"], batch_size, true )
                sleep 10
                salt.runSaltProcessStep(pepperEnv, 'I@maas:region', 'state.apply', ["maas.machines.wait_for_deployed"], batch_size, true)
                sleep 60
            }

            // OpenStack Deployment states

            stage('List target servers') {
                minions = salt.getMinions(pepperEnv, TARGET_SERVERS)

                if (minions.isEmpty()) {
                    throw new Exception("No minion was targeted")
                }

                targetLiveAll = minions.join(' or ')
                common.infoMsg("Found nodes: ${targetLiveAll}")
                common.infoMsg("Selected nodes: ${targetLiveAll}")
            }

            stage('Sync modules') {
                // Sync all of the modules from the salt master.
                salt.syncAll(pepperEnv, targetLiveAll, batch_size)
            }

            stage("Setup repositories") {
                salt.enforceState(pepperEnv, targetLiveAll, 'linux.system.repo', true, true, batch_size)
            }

            stage("Upgrade packages") {
                debian.osUpgradeNode(pepperEnv, targetLiveAll, 'dist-upgrade', true)
                //salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'pkg.upgrade', [], batch_size, true)
            }

            stage("Setup networking") {

                // Apply Linux.system state to install MLNX and to Enable SRIOV VF for NIC'S
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'state.apply', ['linux.system.package'], batch_size, true)
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'state.apply', ['linux.system'], batch_size, true)

                // Apply state 'salt' to install python-psutil for network configuration without restarting salt-minion to avoid losing connection.
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'state.apply',  ['salt', 'exclude=[{\'id\': \'salt_minion_service\'}, {\'id\': \'salt_minion_service_restart\'}, {\'id\': \'salt_minion_sync_all\'}]'], batch_size, true)

                // Restart salt-minion to take effect.
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'service.restart', ['salt-minion'], batch_size, true, 10)

                // Configure networking excluding vhost0 interface.
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'state.apply',  ['linux.network', 'exclude=[{\'id\': \'linux_interface_vhost0\'}]'], batch_size, true)

                if (reboot_node) {
                // Reboot node
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'system.reboot', null, null, true, 5)
                sleep 10
                }
                else {
                // Kill unnecessary processes ifup/ifdown which is stuck from previous state linux.network.
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'ps.pkill', ['ifup'], batch_size, false)
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'ps.pkill', ['ifdown'], batch_size, false)

                // Restart networking to bring UP all interfaces and restart minion to catch network changes.
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'cmd.shell', ["salt-call service.restart networking; salt-call service.restart salt-minion"], batch_size, true, 300)
                }

                salt.minionsReachable(pepperEnv, 'I@salt:master', targetLiveAll, null, 10)

            }

            stage("Highstate compute") {

                // Create AZ before running nova state on CMP nodes
                salt.runSaltProcessStep(pepperEnv, 'I@nova:controller:role:primary', 'state.apply', ['nova.client'], batch_size, true)

                // Execute highstate without state opencontrail.client.
                common.retry(2){
                    salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'state.highstate', ['exclude=opencontrail.client'], batch_size, true)
                }

                // Apply nova state to remove libvirt default bridge virbr0.
                salt.enforceState(pepperEnv, targetLiveAll, 'nova', true, true, batch_size)

                // Execute highstate.
                salt.enforceHighstate(pepperEnv, targetLiveAll, true, true, batch_size)

                // Apply salt and collectd if is present to update information about current network interfaces.
                salt.enforceState(pepperEnv, targetLiveAll, 'salt', true, true, batch_size)
                if(!salt.getPillar(pepperEnv, minions[0], "collectd")['return'][0].values()[0].isEmpty()) {
                    salt.enforceState(pepperEnv, targetLiveAll, 'collectd', true, true, batch_size)
                }
            }

            // host records and fingerprints for compute nodes are generated dynamically - so apply state after node setup
            stage('Update Hosts file and fingerprints') {
                salt.enforceState(pepperEnv, "I@linux:network:host", 'linux.network.host', true, true, batch_size)
                //salt.enforceState(pepperEnv, "I@linux:system", 'openssh', true, true, batch_size)
            }

            // discover added compute hosts
            stage('Discover compute hosts') {
                salt.runSaltProcessStep(pepperEnv, 'I@nova:controller:role:primary', 'state.sls_id', ['nova_controller_discover_hosts', 'nova.controller'], batch_size, true)
            }

            stage("Update/Install monitoring") {
                def slaServers = 'I@prometheus:server'
                def slaMinions = salt.getMinions(pepperEnv, slaServers)

                if (slaMinions.isEmpty()) {
                    common.infoMsg('Monitoring is not enabled on environment, skipping...')
                } else {
                    //Collect Grains
                    salt.enforceState(pepperEnv, targetLiveAll, 'salt.minion.grains', true, true, batch_size)
                    salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'saltutil.refresh_modules', [], batch_size)
                    salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'mine.update', [], batch_size)
                    sleep(5)

                    salt.enforceState(pepperEnv, targetLiveAll, 'prometheus', true, true, batch_size)
                    salt.enforceState(pepperEnv, 'I@prometheus:server', 'prometheus', true, true, batch_size)
                }
            }

        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        }
    }
}
