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
        input("refresh_interval", "enum", title: "How often to refresh the solar data", options: [
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
    return "1.2.0"
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
            schedule("0 59 23 * * ?", refresh, [overwrite: true])
        } else if(settings.refresh_interval == "30"){
            schedule("0 29,59 * ? * *", refresh, [overwrite: true])
        } else {
            schedule("0 59 0/${settings.refresh_interval} ? * *", refresh, [overwrite: true])
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
    def host = "https://api.solcast.com.au/rooftop_sites/${resource_id}/forecasts?format=json&hours=72"
    if(debugLog) log.debug "Host: " + host
    if ( testMode == true ) {
        if (!state.jsonResponse) {
            log.warn "testMode enabled but no saved API response exists yet. Disable testMode and refresh once to populate it."
            return
        }
        def forecasts = new groovy.json.JsonSlurper().parseText(state.jsonResponse)
        log.warn ("testMode enabled, skipping API call and reusing JSON from previous call")
        if(debugLog) log.debug "forecasts: " + JsonOutput.toJson(forecasts)
        processForecasts(forecasts)
    } else {
        apiCall(host)
    }
}

def processForecasts(forecasts) {
    //get the next hour forecast (average of the next two 30 minute periods)
    def firstHour = forecasts.take(2)
    def nextHour = firstHour.sum(0) { it.pv_estimate } / firstHour.size()
    if(logEnable) log.info "nextHour: " + nextHour
    sendEvent(name: "nextHour", value: nextHour, unit: "kWh")
    

    // Get and sum the next 24 hours (48 entries)
    def next24Hours = forecasts.take(48)
    if (debugLog) log.debug "next24Hours: " + next24Hours
    def cumulativeNext24Hours = next24Hours.sum(0) { it.pv_estimate / 2 }
    if (logEnable) log.info "CumulativeNext24Hours: " + cumulativeNext24Hours
    sendEvent(name: "energy", value: cumulativeNext24Hours, unit: "kWh")    
    sendEvent(name: "power", value: Math.round(cumulativeNext24Hours * 1000), unit: "Wh") //convert to wh


    
    //use the hub location's time zone to determine day boundaries
    def tz = location?.timeZone ?: TimeZone.getDefault()

    //get tomorrow at midnight local time and convert it to UTC
    def tomorrowMidnight = Calendar.getInstance(tz) // Get the current time
    tomorrowMidnight.add(Calendar.DATE, 1) // Move to tomorrow
    tomorrowMidnight.set(Calendar.HOUR_OF_DAY, 0) // Set to midnight
    tomorrowMidnight.set(Calendar.MINUTE, 0)
    tomorrowMidnight.set(Calendar.SECOND, 0)
    tomorrowMidnight.set(Calendar.MILLISECOND, 0)

    //a period ending exactly at midnight covers 23:30-00:00, so it belongs to the earlier day
    def forecastToday = forecasts.findAll { parseTimestamp(it.period_end) <= tomorrowMidnight.time }
    if(debugLog) log.debug "forecastToday: " + forecastToday
    def peakToday = forecastToday.max { it.pv_estimate }?.pv_estimate ?: 0
    if(logEnable) log.info "peakToday: " + peakToday
    sendEvent(name: "peakToday", value: peakToday, unit: "kW")
    def cumulativeToday = forecastToday.sum(0) { it.pv_estimate / 2 }
    if(logEnable) log.info "cumulativeToday: " + cumulativeToday
    sendEvent(name: "cumulativeToday", value: cumulativeToday, unit: "kWh")

    //get day after tomorrow at midnight local time and convert it to UTC
    def afterTomorrowMidnight = Calendar.getInstance(tz) // Get the current time
    afterTomorrowMidnight.add(Calendar.DATE, 2) // Move 2 days
    afterTomorrowMidnight.set(Calendar.HOUR_OF_DAY, 0) // Set to midnight
    afterTomorrowMidnight.set(Calendar.MINUTE, 0)
    afterTomorrowMidnight.set(Calendar.SECOND, 0)
    afterTomorrowMidnight.set(Calendar.MILLISECOND, 0)

    //limit results to tomorrow
    def forecastTomorrow = forecasts.findAll {
        def periodEnd = parseTimestamp(it.period_end)
        periodEnd > tomorrowMidnight.time && periodEnd <= afterTomorrowMidnight.time
    }
    if(debugLog) log.debug "forecastTomorrow: " + forecastTomorrow
    def peakTomorrow = forecastTomorrow.max { it.pv_estimate }?.pv_estimate ?: 0
    if(logEnable) log.info "peakTomorrow: " + peakTomorrow
    sendEvent(name: "peakTomorrow", value: peakTomorrow, unit: "kW")
    def cumulativeTomorrow = forecastTomorrow.sum(0) { it.pv_estimate / 2 }
    if(logEnable) log.info "cumulativeTomorrow: " + cumulativeTomorrow
    sendEvent(name: "cumulativeTomorrow", value: cumulativeTomorrow, unit: "kWh")

    
    //get 3 days from today at midnight local time and convert it to UTC
    def threeDaysMidnight = Calendar.getInstance(tz) // Get the current time
    threeDaysMidnight.add(Calendar.DATE, 3) // Move 3 days
    threeDaysMidnight.set(Calendar.HOUR_OF_DAY, 0) // Set to midnight
    threeDaysMidnight.set(Calendar.MINUTE, 0)
    threeDaysMidnight.set(Calendar.SECOND, 0)
    threeDaysMidnight.set(Calendar.MILLISECOND, 0)

    //limit results to day after tomorrow
    def forecastDayAfterTomorrow = forecasts.findAll {
        def periodEnd = parseTimestamp(it.period_end)
        periodEnd > afterTomorrowMidnight.time && periodEnd <= threeDaysMidnight.time
    }
    if(debugLog) log.debug "forecastDayAfterTomorrow: " + forecastDayAfterTomorrow
    def peakDayAfterTomorrow = forecastDayAfterTomorrow.max { it.pv_estimate }?.pv_estimate ?: 0
    if(logEnable) log.info "peakDayAfterTomorrow: " + peakDayAfterTomorrow
    sendEvent(name: "peakDayAfterTomorrow", value: peakDayAfterTomorrow, unit: "kW")
    def cumulativeDayAfterTomorrow = forecastDayAfterTomorrow.sum(0) { it.pv_estimate / 2 }
    if(logEnable) log.info "cumulativeDayAfterTomorrow: " + cumulativeDayAfterTomorrow
    sendEvent(name: "cumulativeDayAfterTomorrow", value: cumulativeDayAfterTomorrow, unit: "kWh")

	state.lastUpdate = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
	sendEvent(name: "lastUpdate", value: state.lastUpdate)

}

//parse an ISO 8601 timestamp from Solcast, e.g. 2024-01-01T12:30:00.0000000Z
def parseTimestamp(String timestamp) {
    //SimpleDateFormat can't handle 7 digit fractional seconds, and Solcast periods fall on whole seconds
    def iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX")
    return iso.parse(timestamp.replaceFirst(/\.\d+/, ""))
}

def apiCall(host) {

    if(debugLog) log.debug host
    //send the API key as a header so it doesn't appear in the URL or logs
    def params = [
        uri: host,
        headers: ["Authorization": "Bearer ${api_key}".toString()],
        timeout: 30
    ]
    asynchttpGet("handleApiResponse", params)
}

def handleApiResponse(response, data) {
    if (response.hasError()) {
        if (debugLog) {
            log.debug response.getErrorMessage()
        }
        def httpError = response.getStatus()
        if ( httpError == 429 ) {
            log.error("http 429 - rate limit error. You have sent too many API requests today.")
        } else {
            log.error("http error ${httpError}. Enable debugging for further info")
        }
        return
    }

    def forecasts
    try {
        forecasts = response.getJson()?.forecasts
    }
    catch (exception) {
        if (debugLog) {
            log.debug exception
        }
        log.error("unable to parse API response. Enable debugging for further info")
        return
    }
    if (!forecasts) {
        log.error("API response did not contain any forecasts")
        return
    }
    if(debugLog) log.debug JsonOutput.toJson(forecasts)
    state.jsonResponse = JsonOutput.toJson(forecasts)
    processForecasts(forecasts)
}
