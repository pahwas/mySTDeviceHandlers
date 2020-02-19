/**
 *  Hive Smart Siren
 *       Author: Satinder Pahwa
 *       This is a device handler for Smartthings platform for the Hive Siren from UK,
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Note that off will turn both the light and siren OFF
 * Revision:  V2:  Changed to separate the ligting on() from Siren() on.  Any command
 *                 to turn off, will turn both siren and light off (no way to separate
 *                 the off command between the two unless we use child devices.
 */

import physicalgraph.zigbee.clusters.iaszone.ZoneStatus
import physicalgraph.zigbee.zcl.DataType

metadata {
    definition(name: "Hive Smart Siren3", namespace: "satinderpahwa", author: "Satinder", mnmn: "SmartThings", vid: "hive", ocfDeviceType: "hive.siren") {
        capability "Actuator"
        capability "Alarm"
        capability "Switch"
        capability "Configuration"
        capability "Health Check"
        capability "Light"

        /* command "lightOn" */
        /* command "lightOff" */

        fingerprint profileId: "0104", inClusters: "0000 0001 0003 0004 0500 0502 0B05", outClusters: "0019", manufacturer: "LDS", model: "SIREN001", deviceJoinName: "Hive Siren" // Ozom Siren - SRAC-23ZBS
        fingerprint profileId: "0104", inClusters: "0000 0004 0005 0006 0008", manufacturer: "LDS", model: "SIREN001", deviceJoinName: "Siren Light"
}

    tiles {
        standardTile("alarm", "device.alarm", width: 2, height: 2) {
            state "off", label:'off', action:'alarm.siren', icon:"st.secondary.siren", backgroundColor:"#ffffff"
            state "siren", label:'siren!', action:'Off', icon:"st.secondary.siren", backgroundColor:"#e86d13"
        }
        standardTile(name: "light", type:"device.switch", width: 2, height: 2) {
            state "off", label:'off', action:'on', icon:"st.lights.philips.hue-single", backgroundColor:"#ffffff", nextState: "on"
            state "on", label:'On', action:'off', icon:"st.lights.philips.hue-single", backgroundColor:"#e86d13", nextState: "off"
        }

        main "alarm"
        details(["alarm","light"])
    }
}

private getDEFAULT_MAX_DURATION() { 0x00B4 }
private getDEFAULT_DURATION() { 0xFFFE }

private getIAS_WD_CLUSTER() { 0x0502 }

private getATTRIBUTE_IAS_WD_MAXDURATION() { 0x0000 }
private getATTRIBUTE_IAS_ZONE_STATUS() { 0x0002 }

private getCOMMAND_IAS_WD_START_WARNING() { 0x00 }
private getCOMMAND_DEFAULT_RESPONSE() { 0x0B }

private getIAS_WD_ZONE_CLUSTER() { 0x0500 }
private getIAS_LIGHT_CLUSTER() { 0x0006 }

def turnOffAlarmTile(){
    sendEvent(name: "alarm", value: "off")
    sendEvent(name: "switch", value: "off")
}

def turnOnAlarmTile(){
    sendEvent(name: "alarm", value: "siren")
    sendEvent(name: "switch", value: "on")
}

def installed() {
    def hub = location.hubs[0]
    log.info "Installed routine called"
    sendCheckIntervalEvent()
    state.maxDuration = DEFAULT_MAX_DURATION
    turnOffAlarmTile()
    log.info "zigbeeEui: ${hub.zigbeeEui}"
    // zigbee.writeAttribute(0x0500, 0x0010, 0xf0, swapEndianHex(hub.zigbeeEui))
    zigbee.writeAttribute(0x0500,0x0010,DataType.STRING_CHAR,${hub.zigbeeEui})
}

def parse(String description) {
   // log.debug "Parsing '${description}'"
    log.debug "parse description: $description"
    Map map = zigbee.getEvent(description)
    if (!map) {
        if (description?.startsWith('enroll request')) {
            List cmds = zigbee.enrollResponse()
            log.debug "enroll response: ${cmds}"
            return cmds
        } else {
            Map descMap = zigbee.parseDescriptionAsMap(description)
            if (descMap?.clusterInt == IAS_WD_CLUSTER) {
                def data = descMap.data

                Integer parsedAttribute = descMap.attrInt
                Integer command = Integer.parseInt(descMap.command, 16)
                if (parsedAttribute == ATTRIBUTE_IAS_WD_MAXDURATION && descMap?.value) {
                    state.maxDuration = Integer.parseInt(descMap.value, 16)
                } else if (command == COMMAND_DEFAULT_RESPONSE) {
                    Boolean isSuccess = Integer.parseInt(data[-1], 16) == 0
                    Integer receivedCommand = Integer.parseInt(data[-2], 16)
                    if (receivedCommand == COMMAND_IAS_WD_START_WARNING && isSuccess){
                        if(state.alarmOn){
                            turnOnAlarmTile()
                            runIn(state.lastDuration, turnOffAlarmTile)
                        } else {
                            turnOffAlarmTile()
                        }
                    }
                }
            }
        }
    }
    log.debug "Parse returned $map"
    def results = map ? createEvent(map) : null
    log.debug "parse results: " + results
    return results
}

private sendCheckIntervalEvent() {
    log.info "Send check interval started"
    sendEvent(name: "checkInterval", value: 30 * 60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])
}

def ping() {
    return zigbee.readAttribute(zigbee.IAS_ZONE_CLUSTER, zigbee.ATTRIBUTE_IAS_ZONE_STATUS)
}

def configure() {
    sendCheckIntervalEvent()


    def cmds = zigbee.enrollResponse() +
            zigbee.writeAttribute(IAS_WD_CLUSTER, ATTRIBUTE_IAS_WD_MAXDURATION, DataType.UINT16, DEFAULT_DURATION) +
            zigbee.configureReporting(zigbee.IAS_ZONE_CLUSTER, zigbee.ATTRIBUTE_IAS_ZONE_STATUS, DataType.BITMAP16, 0, 180, null)
    log.debug "configure: " + cmds

    return cmds
}

def siren() {
    log.debug "siren()"
    log.debug "on()"

    state.alarmOn = true
    def warningDuration = state.maxDuration ? state.maxDuration : DEFAULT_MAX_DURATION
    state.lastDuration = warningDuration

    // start warning, burglar mode, no strobe, siren very high
    zigbee.command(IAS_WD_CLUSTER, COMMAND_IAS_WD_START_WARNING, "13", DataType.pack(warningDuration, DataType.UINT16), "00", "00")
}

def on() {
    log.debug "lightOn()"

    state.lightOn = true
    zigbee.command(getIAS_LIGHT_CLUSTER(),0x01,"", [destEndpoint: 02])
}

def off() {
    log.debug "off()"

    state.alarmOn = false
    state.lightOn = false
    zigbee.command(IAS_WD_CLUSTER, COMMAND_IAS_WD_START_WARNING, "00", "0000", "00", "00") + zigbee.command(getIAS_LIGHT_CLUSTER(),0x00,"", [destEndpoint: 02])
}

//def lightOff() {
//    log.debug "lightOff()"

//    state.lightOn = true
//    zigbee.command(getIAS_LIGHT_CLUSTER(),0x00,"", [destEndpoint: 02])
//}

//def lightOn() {
//    log.debug "lightOn()"

//    state.lightOn = false
//    zigbee.command(getIAS_LIGHT_CLUSTER(),0x01,"", [destEndpoint: 02])
//}
