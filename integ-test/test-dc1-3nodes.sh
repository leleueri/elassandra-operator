#!/usr/bin/env bash

source $(dirname $0)/test-lib.sh

#create_resource_group
#create_aks_cluster 3

#helm_init
#install_elassandra_operator

test_start
install_elassandra_datacenter default cl1 dc1 1
java -jar ../java/edctl/build/libs/edctl.jar watch --health GREEN

scale_elassandra_datacenter cl1 dc1 2
java -jar ../java/edctl/build/libs/edctl.jar watch -p RUNNING -r 2

scale_elassandra_datacenter cl1 dc1 3
java -jar ../java/edctl/build/libs/edctl.jar watch -p RUNNING -r 3


park_elassandra_datacenter cl1 dc1
java -jar ../java/edctl/build/libs/edctl.jar watch -p PARKED -r 0
sleep 10
unpark_elassandra_datacenter cl1 dc1
java -jar ../java/edctl/build/libs/edctl.jar watch -p RUNNING --health GREEN -r 3


reaper_enable cl1 dc1
java -jar ../java/edctl/build/libs/edctl.jar watch --reaper REGISTERED
sleep 10
reaper_disable cl1 dc1
java -jar ../java/edctl/build/libs/edctl.jar watch --reaper NONE

#downgrade_elassandra_datacenter cl1 dc1
#java -jar ../java/edctl/build/libs/edctl.jar watch -p RUNNING

#add_memory_elassandra_datacenter
#java -jar ../java/edctl/build/libs/edctl.jar watch -p RUNNING

#scale_elassandra_datacenter cl1 dc1 1
#java -jar ../java/edctl/build/libs/edctl.jar watch -p RUNNING

uninstall_elassandra_datacenter
echo "Test SUCCESSFUL"
test_end