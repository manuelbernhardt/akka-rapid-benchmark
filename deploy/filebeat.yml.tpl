filebeat.inputs:
  - type: log
    enabled: true
    paths:
      - /tmp/akka-*.log
    scan_frequency: 1m
    json.keys_under_root: true
cloud.id: "${cloud_id}"
setup.kibana:
  host: "${kibana_host}"
cloud.auth: "${cloud_auth}"
processors:
 - decode_json_fields:
     fields: ["message"]
     process_array: false
     max_depth: 1
     target: ""
     overwrite_keys: false
     add_error_key: true
max_procs: 1
queue.mem:
  events: 4096
  flush.min_events: 2048
  flush.timeout: 10s