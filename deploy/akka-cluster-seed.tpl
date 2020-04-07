check process akka with pidfile /tmp/akka.pid
  every 2 cycles
  start program = "/opt/akka/bin/akka-rapid-benchmark -J-Xms8g -J-Xmx12g -J-javaagent:/opt/elastic-apm-agent-1.15.0.jar -Delastic.apm.service_name=${service_name} -Delastic.apm.server_urls=${apm_server_url} -Delastic.apm.application_packages=io.bernhardt -Delastic.apm.secret_token=${apm_token}"
  stop program = "/bin/sh -c 'kill -s SIGTERM `cat /tmp/akka.pid`'"