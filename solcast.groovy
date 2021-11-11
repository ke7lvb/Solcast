metadata {
    definition(
        name: "Solcast",
        namespace: "ke7lvb",
        author: "Ryan Lundell",
        importUrl: "https://raw.githubusercontent.com/ke7lvb/Solcast/main/solcast.groovy",
    ) {
        capability "Refresh"
        capability "EnergyMeter"

        attribute "energy", "number"
        attribute "48 Hour Estimate", "number"
        attribute "72 Hour Estimate", "number"
        attribute "lastUpdate", "string"
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable Info logging", defaultValue: true, description: ""
        input name: "debugLog", type: "bool", title: "Enable Debug logging", defaultValue: true, description: ""
        input name: "api_key", type: "string", title: "API Key", required: true
        input name: "resource_id", type: "string", title: "Site Resource ID", required: true
        input("refresh_interval", "enum", title: "How often to refresh the battery data", options: [
            0: "Do NOT update",
            1: "1 Hour",
            3: "3 Hours",
            8: "8 Hours",
            12: "12 Hours",
            24: "Daily",
        ], required: true, defaultValue: "3")
    }
}

def version() {
    return "1.0.2"
}

def installed() {
    if (logEnable) log.info "Driver installed"

    state.version = version()
}

def uninstalled() {
    unschedule(refresh)
    if (logEnable) log.info "Driver uninstalled"
}

def updated() {
    if (logEnable) log.info "Settings updated"
    if (settings.refresh_interval != "0") {
        //refresh()
        if (settings.refresh_interval == "24") {
            schedule("51 7 2 ? * * *", refresh, [overwrite: true])
        } else {
            schedule("51 7 */${settings.refresh_interval} ? * * *", refresh, [overwrite: true])
        }
    }else{
        unschedule(refresh)
    }
    state.version = version()
}

import groovy.json.JsonOutput;
def refresh() {
    outputTZ = TimeZone.getTimeZone('UTC')

    host = "https://api.solcast.com.au/rooftop_sites/${resource_id}/forecasts?format=json&api_key=${api_key}"
    if(debugLog) log.debug host
    forecasts = httpGet([uri: host]) {resp -> def respData = resp.data.forecasts}
    if(debugLog) log.deubg JsonOutput.toJson(forecasts)
    def next24 = 0;
    def next24High = 0;
    def next24Low = 0;
    def next48 = 0;
    def next48High = 0;
    def next48Low = 0;
    def next72 = 0;
    def next72High = 0;
    def next72Low = 0;
    for(int x=0; x<144; x++){
        pv_estimate = forecasts[x].pv_estimate/2
        pv_estimate_high = forecasts[x].pv_estimate90/2
        pv_estimate_low = forecasts[x].pv_estimate10/2

        if(x < 48){
            next24 = next24 + pv_estimate
            next24High = next24High + pv_estimate_high
            next24Low = next24Low + pv_estimate_low
        }
        if(x < 96){
            next48 = next48 + pv_estimate
            next48High = next48High + pv_estimate_high
            next48Low = next48Low + pv_estimate_low
        }
        next72 = next72 + pv_estimate
        next72High = next72High + pv_estimate_high
        next72Low = next72Low + pv_estimate_low
    }

    state.next24 = next24
    sendEvent(name: "energy", value: next24)
    state.next24High = next24High
    state.next24Low = next24Low
    state.next48 = next48
    sendEvent(name: "48 Hour Estimate", value: next48)
    state.next48High = next48High
    state.next48Low =next48Low
    state.next72 = next72
    sendEvent(name: "72 Hour Estimate", value: next72)
    state.next72High = next72High
    state.next72Low = next72Low

    now = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'",outputTZ)
    state.lastUpdate = timeToday(now, location.timeZone)
    sendEvent(name: "lastUpdate", value: state.lastUpdate)
}
