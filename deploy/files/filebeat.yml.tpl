filebeat.inputs:
  - type: log
    enabled: true
    paths:
      - /tmp/akka-*.log
    json.keys_under_root: true
cloud.id: "${cloud_id}"
setup.kibana:
  host: "${kibana_host}"
cloud.auth: "${cloud_auth}"
processors:
 - add_cloud_metadata: ~
 - decode_json_fields:
     fields: ["message"]
     process_array: false
     max_depth: 1
     target: ""
     overwrite_keys: false
     add_error_key: true
max_procs: 1