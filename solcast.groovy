metadata {
    definition(
        name: "Solcast",
        namespace: "ke7lvb",
        author: "Ryan Lundell",
        importUrl: "https://raw.githubusercontent.com/ke7lvb/Solcast/main/solcast.groovy",
    ) {
        capability "Refresh"
        capability "EnergyMeter"
        capability "PowerMeter"

        attribute "energy", "number"
        attribute "power", "number"
        attribute "nextHour", "number"
        attribute "cumulativeToday", "number"
        attribute "cumulativeTomorrow", "number"
        attribute "cumulativeDayAfterTomorrow", "number"
        attribute "peakToday", "number"
        attribute "peakTomorrow", "number"
        attribute "peakDayAfterTomorrow", "number"
        attribute "lastUpdate", "string"
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable Info logging", defaultValue: true, description: ""
        input name: "debugLog", type: "bool", title: "Enable Debug logging", defaultValue: true, description: ""
        input name: "api_key", type: "string", title: "API Key", required: true
        input name: "resource_id", type: "string", title: "Site Resource ID", required: true
        input("refresh_interval", "enum", title: "How often to refresh the battery data", options: [
            0: "Do NOT update",
            30: "30 minutes",
            1: "1 Hour",
            3: "3 Hours",
            8: "8 Hours",
            12: "12 Hours",
            24: "Daily",
        ], required: true, defaultValue: "3")
        input name: "testMode", type: "bool", title: "Test Mode", defaultValue: false, description: "Reuse result from API to prevent too many API calls"

    }
}
def version() {
    return "1.1.1"
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
        } else if(settings.refresh_interval == "30"){
            schedule("51 */30 * ? * *", refresh, [overwrite: true])
        } else {
            schedule("51 7 */${settings.refresh_interval} ? * * *", refresh, [overwrite: true])
        }
    }else{
        unschedule(refresh)
    }
    state.version = version()
}

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.TimeZone
import groovy.json.JsonOutput;
def refresh() {
    host = "https://api.solcast.com.au/rooftop_sites/${resource_id}/forecasts?format=json&api_key=${api_key}&hours=72"
    if(debugLog) log.debug "Host: " + host
    if ( testMode == true ) {
        forecasts = new groovy.json.JsonSlurper().parseText(state.jsonResponse)
        log.warn ("testMode enabled, skipping API call and reusing JSON from previous call")
        if(debugLog) log.debug "forecasts: " + JsonOutput.toJson(forecasts)
    } else {
        forecasts = apiCall(host)
    }
    
    //get the next hour forecast
    def nextHour = (forecasts[0]?.pv_estimate + forecasts[1]?.pv_estimate) / 2
    if(logEnable) log.info "nextHour: " + nextHour
    sendEvent(name: "nextHour", value: nextHour)
    

    // Get and sum the next 24 hours (48 entries)
    def next24Hours = forecasts.take(48)
    if (debugLog) log.debug "next24Hours: " + next24Hours
    def cumulativeNext24Hours = next24Hours.sum { it.pv_estimate / 2 }
    if (logEnable) log.info "CumulativeNext24Hours: " + cumulativeNext24Hours
    sendEvent(name: "energy", value: cumulativeNext24Hours)    
    sendEvent(name: "power", value: Math.round(cumulativeNext24Hours * 1000) ) //convert to wh


    
    //get tomorrow at midnight local time and convert it to UTC
    def tomorrowMidnight = Calendar.getInstance() // Get the current time
    tomorrowMidnight.add(Calendar.DATE, 1) // Move to tomorrow
    tomorrowMidnight.set(Calendar.HOUR_OF_DAY, 0) // Set to midnight
    tomorrowMidnight.set(Calendar.MINUTE, 0)
    tomorrowMidnight.set(Calendar.SECOND, 0)

    def sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
    sdf.setTimeZone(TimeZone.getTimeZone("UTC")) // Convert to UTC

    def utcTomorrowMidnight = sdf.format(tomorrowMidnight.time)

    //use the UTC timestamp to limit results to end of today
    forecastToday = forecasts.findAll { it.period_end < utcTomorrowMidnight}
    if(debugLog) log.debug "forecastToday: " + forecastToday
    peakToday = forecastToday.max { it.pv_estimate }?.pv_estimate
    if(logEnable) log.info "peakToday: " + peakToday
    sendEvent(name: "peakToday", value: peakToday)
    cumulativeToday = forecastToday.sum() { it.pv_estimate / 2 }
    if(logEnable) log.info "cumulativeToday: " + cumulativeToday
    sendEvent(name: "cumulativeToday", value: cumulativeToday)

    //get day after tomorrow at midnight local time and convert it to UTC
    def afterTomorrowMidnight = Calendar.getInstance() // Get the current time
    afterTomorrowMidnight.add(Calendar.DATE, 2) // Move 2 days
    afterTomorrowMidnight.set(Calendar.HOUR_OF_DAY, 0) // Set to midnight
    afterTomorrowMidnight.set(Calendar.MINUTE, 0)
    afterTomorrowMidnight.set(Calendar.SECOND, 0)

    def utcAfterTomorrowMidnight = sdf.format(afterTomorrowMidnight.time)

    //use the UTC timestamp to limit results to tomorrow
    forecastTomorrow = forecasts.findAll { it.period_end >= utcTomorrowMidnight && it.period_end < utcAfterTomorrowMidnight}
    if(debugLog) log.debug "forecastTomorrow: " + forecastTomorrow
    peakTomorrow = forecastTomorrow.max { it.pv_estimate }?.pv_estimate
    if(logEnable) log.info "peakTomorrow: " + peakTomorrow
    sendEvent(name: "peakTomorrow", value: peakTomorrow)
    cumulativeTomorrow = forecastTomorrow.sum() { it.pv_estimate / 2 }
    if(logEnable) log.info "cumulativeTomorrow: " + cumulativeTomorrow
    sendEvent(name: "cumulativeTomorrow", value: cumulativeTomorrow)

    
    //get 3 days from today at midnight local time and convert it to UTC
    def threeDaysMidnight = Calendar.getInstance() // Get the current time
    threeDaysMidnight.add(Calendar.DATE, 3) // Move 3 days
    threeDaysMidnight.set(Calendar.HOUR_OF_DAY, 0) // Set to midnight
    threeDaysMidnight.set(Calendar.MINUTE, 0)
    threeDaysMidnight.set(Calendar.SECOND, 0)

    def utcThreeDaysMidnight = sdf.format(threeDaysMidnight.time)

    //use the UTC timestamp to limit results to day after tomorrow
    forecastDayAfterTomorrow = forecasts.findAll { it.period_end >= utcAfterTomorrowMidnight && it.period_end < utcThreeDaysMidnight}
    if(debugLog) log.debug "forecastDayAfterTomorrow: " + forecastDayAfterTomorrow
    peakDayAfterTomorrow = forecastDayAfterTomorrow.max { it.pv_estimate }?.pv_estimate
    if(logEnable) log.info "peakDayAfterTomorrow: " + peakDayAfterTomorrow
    sendEvent(name: "peakDayAfterTomorrow", value: peakDayAfterTomorrow)
    cumulativeDayAfterTomorrow = forecastDayAfterTomorrow.sum() { it.pv_estimate / 2 }
    if(logEnable) log.info "cumulativeDayAfterTomorrow: " + cumulativeDayAfterTomorrow
    sendEvent(name: "cumulativeDayAfterTomorrow", value: cumulativeDayAfterTomorrow)

	state.lastUpdate = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'")
	sendEvent(name: "lastUpdate", value: state.lastUpdate)

}

def apiCall(host) {

    if(debugLog) log.debug host
    try {
        forecasts = httpGet([uri: host]) {resp -> def respData = resp.data.forecasts}
        if(debugLog) log.debug JsonOutput.toJson(forecasts)
        state.jsonResponse = JsonOutput.toJson(forecasts)
        return forecasts
    }
    catch (groovyx.net.http.HttpResponseException exception) {
        if (debugLog) {
            log.debug exception
        }
        def httpError = exception.getStatusCode()
        if ( httpError == 429 ) {
            log.error("http 429 - rate limit error. You have sent too many API requests today.")
        } else {
            log.error("http error ${httpError}. Enable debugging for further info")
        }
        return false
    } 
    catch (exception) {
        if (debugLog) {
            log.debug exception
        }
        log.error("unknown error during API Call. Enable debugging for further info")
        return false
     }
}
