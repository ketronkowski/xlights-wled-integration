# Daily pre-show check

A read-only go / no-go check that runs before the show every night and tells you,
by push and email, whether every controller, FPP, power and the public pages are
ready. It never changes anything: it only reads status.

## What it checks

| Layer | Check | Blocker (NO-GO) when | Warning when |
| --- | --- | --- | --- |
| Setup | xLights layout uploaded | no usable layout | |
| Boards | Answering (3 tries) | board does not answer | |
| Boards | LED count vs xLights | WLED total differs from xLights (later pixels shift) | |
| Boards | Firmware | | not `preshow.wled.expected-firmware` |
| Boards | Restarts in the last 24 h | | a board restarted on its own (boards powering up together count as one circuit power-up and pass) |
| Boards | Free memory, WiFi signal | | free heap under 30 KB, RSSI under -75 dBm (WiFi boards only) |
| Boards | Pre-show state | | visibly lit (color or effect) before the show, nightlight on, or receiving live data before the show / from something other than FPP |
| Boards | Hardening | | OTA lock off, ArduinoOTA on, fallback AP not button-only |
| FPP | Answering, fppd running, scheduler enabled | any fails | |
| FPP | Tonight's schedule | no show playlist covering show start | |
| FPP | Playlist files (`/api/playlists/validate`) | a missing sequence or media file, empty or absent playlist | |
| FPP | Outputs | no active output to a board, or outputs disabled | channel count differs from xLights |
| FPP | Clock, CPU temperature, disk, volume, MQTT, under-voltage, FPP warnings, restarts | | out of range |
| Power | Show circuit relay and voltage (Home Assistant) | relay off, under 110 V or unavailable | |
| Power | Standby current | | above a fixed ceiling, or well above the usual pre-show reading |
| Power | Smart plugs feeding boards | plug off or unavailable | |
| Web | Each configured URL | page down (when `blocker: true`) | page down, expected text missing, TLS certificate under 14 days |

Verdict: any blocker is **NO-GO**, warnings alone are **GO with warnings**, otherwise **GO**.
NO-GO is information: the check never stops or starts the show.

## When it runs

Scheduled passes (cron in `preshow.zone`, only between `season-start` and `season-end`,
and only while the optional Home Assistant kill switch is on):

| Pass | Default | Push | Email |
| --- | --- | --- | --- |
| early | 2:00 PM | on NO-GO | on NO-GO |
| recheck | 4:15 PM | on NO-GO | on NO-GO |
| final | 4:50 PM | always | always |
| last-look | 4:58 PM | on NO-GO | on NO-GO |

Every run also writes its result to a Home Assistant sensor (`sensor.preshow_status`
by default) so Home Assistant can alert when a run did **not** happen (below).

Manual runs: the **Pre-show check** page in the app (`/preshow.html`), or
`POST /api/preshow/run` with `{"pass":"manual","notify":false}`.

Runs are kept under `<data-dir>/preshow/runs` for `keep-days` (60).

## Configuration

All settings live under `preshow.*` and can be set as environment variables
(`PRESHOW_HA_TOKEN`) or in an extra config file
(`SPRING_CONFIG_ADDITIONAL_LOCATION=/config/preshow.yml`):

```yaml
preshow:
  enabled: true
  zone: America/New_York
  season-start: 2026-11-26
  season-end: 2026-12-31
  first-show-night: 2026-11-26   # optional: earlier season days are dry runs (missing FPP schedule only warns)
  show-start: "17:00"
  wled:
    expected-firmware: 16.0.1
    expected-preset: 1
    ignore: []                  # xLights controllers not in the show
  fpp:
    host: 192.168.6.10          # blank = from the xLights layout
    show-playlists: [Remote]
    validate-playlists: [Remote, Background]
    expected-volume: 72
  ha:
    url: http://homeassistant.local:8123
    token: ${PRESHOW_HA_TOKEN}  # long-lived access token
    notify-service: mobile_app_my_phone
    voltage-entity: sensor.show_circuit_voltage
    current-entity: sensor.show_circuit_current
    power-entity: sensor.show_circuit_power      # used when there is no current sensor
    circuit-switch-entity: switch.show_circuit
    plugs:
      Presents: switch.presents_plug
    enabled-entity: input_boolean.preshow_enabled
    report-url: https://xlights-wled.example.net
  web:
    - name: Viewer page
      url: https://lights.example.org
      blocker: true
      contains: "Holiday Lights"
  email:
    to: me@example.com
    from: ${GMAIL_FROM_ADDRESS}
    password: ${GMAIL_APP_PASSWORD}   # Gmail app password, smtp.gmail.com:587 STARTTLS
```

FPP's UI password comes from the existing `FPP_USERNAME` / `FPP_PASSWORD` settings.

## Home Assistant pieces

Add these to Home Assistant (for example as a package). The automation is the
backstop: if the app is down or can't reach the house, no result arrives and
Home Assistant says so, so silence never looks like a pass.

```yaml
input_boolean:
  preshow_enabled:
    name: Pre-show test enabled
    icon: mdi:clipboard-check-outline

automation:
  - alias: Pre-show check did not run
    triggers:
      - trigger: time
        at: "16:53:00"
    conditions:
      - condition: state
        entity_id: input_boolean.preshow_enabled
        state: "on"
      - condition: template
        value_template: >
          {% set fin = state_attr('sensor.preshow_status', 'finished_at') %}
          {{ fin is none or (now() - as_datetime(fin)).total_seconds() > 900 }}
    actions:
      - action: notify.mobile_app_my_phone
        data:
          title: "Pre-show check did not run"
          message: "No result from the 4:50 PM pre-show check. Check the xLights WLED app."
```

Limit the automation to show nights with an extra condition (a date range or a
`input_boolean.show_night`) if you don't want it outside the season.

## iPhone camera feed (Home Assistant)

The live stream box (live-01) serves `GET http://192.168.4.10:9998/status`:
`{"phone_ready": bool, "show_ready": bool, "feed_source": "phone"|"camera-4k"|"camera-720p"|"none", "updated": "..."}`.
Home Assistant polls it, the pre-show check reads the HA entities (`preshow.ha.phone-feed-entity`,
`preshow.ha.feed-source-entity`), and an automation pushes when the phone drops during the show.

```yaml
# packages/live_feed.yaml
rest:
  - resource: http://192.168.4.10:9998/status
    scan_interval: 15
    timeout: 5
    binary_sensor:
      - name: Live iPhone feed
        unique_id: live_iphone_feed
        value_template: "{{ value_json.phone_ready }}"
        device_class: connectivity
    sensor:
      - name: Live feed source
        unique_id: live_feed_source
        value_template: "{{ value_json.feed_source }}"

automation:
  - id: live_iphone_feed_dropped
    alias: Live iPhone feed dropped during show
    mode: single
    trigger:
      - platform: state
        entity_id: binary_sensor.live_iphone_feed
        to: ["off", "unavailable"]
        for: "00:01:00"
    condition:
      - condition: time
        after: "17:00:00"
        before: "22:00:00"
    action:
      - service: notify.mobile_app_kevin_s_pixel
        data:
          title: "iPhone camera dropped"
          message: >-
            Stream is on {{ states('sensor.live_feed_source') }}. Open Blackmagic Camera and
            toggle Live Stream off and on.
          data: { tag: live-iphone-feed }
      - wait_for_trigger:
          - platform: state
            entity_id: binary_sensor.live_iphone_feed
            to: "on"
            for: "00:00:30"
        timeout: "05:00:00"
      - service: notify.mobile_app_kevin_s_pixel
        data:
          title: "iPhone camera back"
          message: "Stream is on {{ states('sensor.live_feed_source') }} again."
          data: { tag: live-iphone-feed }
```

`mode: single` plus the wait means one drop sends one alert and one recovery notice; the shared
`tag` replaces the alert on the phone when the recovery arrives.
