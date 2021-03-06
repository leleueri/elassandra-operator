Operations
**********

Edctl utility
=============

The **edctl** utility (Elassandra Datacenter Ctl) allow to synchronously wait for status condition on an Elassandra Datacenter or Task.

For example you can wait a datacenter reach the GREEN state with 3 replicas:

.. code::

    edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN --replicas 2
    Waiting elassandra datacenter name=elassandra-cl1-dc1 namespace=ns4 health=GREEN replicas=2 timeout=600s
    ADDED : elassandra-cl1-dc1 phase=RUNNING heath=GREEN replicas=1 reaper=NONE
    MODIFIED : elassandra-cl1-dc1 phase=RUNNING heath=GREEN replicas=2 reaper=NONE
    done 111431ms

Or wait an Elassandra task terminates:

.. code::

    edctl watch-task -n replication-add-$$ -ns $NS --phase SUCCEED

Datacenter operations
=====================

Check the datacenter status
___________________________

You can check the Elassandra datacenter status as follow (**edc** is the short name for elassandradatacenters):

.. code::

    kubectl get edc elassandra-cl1-dc1 -o yaml

Scale Up/Down a datacenter
__________________________

You can scale up or scale down a datacenter by setting the ``replicas`` attribute in the datacenter spec.

.. code-block:: bash

   kubectl patch -n default elassandradatacenters elassandra-mycluster-mydatacenter --type merge --patch '{ "spec" : { "replicas" : 6 }}'

When scaling up:

* The datacenter ``status.needCleanup`` is set to true right after adding an Elassandra node, indicating a cleanup should be
  done on all nodes in the datacenter. This is up to the administrator to later run a **cleanup** task to cleanup keys no longer belonging to the nodes.
* Once the datacenter runs the desired number of nodes, the replication factor of managed keyspaces are automatically
  increased up to the target replication factor, and, in order to get consistent reads with consistency level of two or more,
  a repair task is played each time the replication factor is increased by one up to the target replication factor.

When scaling down:

* The removed Elassandra nodes are decommissioned and their data are streamed to the remaining nodes (it can takes a while depending on the data volumes hosted on the removed nodes).
* Once the datacenter runs the desired number of nodes, the replication factor of managed keyspaces are adjusted to the number of nodes if needed (The Replication Factor of a keyspace should not
  be greater than the number of nodes in the datacenter).

.. warning::

    When scaling down, you currently have to delete PVCs of the removed Elassandra nodes.
    If you scale-up and re-use these old PVCs, Elassandra nodes won't start until you delete old PVCs because Cassandra
    hosts IDs stored on these disks were previously used in the cluster, and you will get the following error message:

    ..code::

        org.apache.cassandra.exceptions.ConfigurationException: This node was decommissioned and will not rejoin the ring unless cassandra.override_decommission=true has been set, or all existing data is removed and the node is bootstrapped again

Rolling update
______________

You can upgrade/downgrade or change any setting by updating the datacenter spec. Such a change trigger a rolling restart of cassandra racks.
The elassandra-operator trigger one StatefulSet rolling update at a time (update on Cassandra rack at a time, rackStatus.progressState=UPDATING).
Each rack rolling restart is managed by the StatefulSet RollingUpdate

In the following example, we upgrade the elassandra image.

.. code-block:: bash

    kubectl patch elassandradatacenter elassandra-cl1-dc1 --type="merge" --patch '{"spec": { "elassandraImage": "strapdata/elassandra-node:6.8.4.5" }}'

Rolling restart
_______________

In order to rolling restart all Elassandra nodes, just add a dummy annotation to the spec.podTemplate, and this triggers a rolling restart of rack statefulsets one by one.

.. code-block:: bash

    kubectl patch elassandradatacenter elassandra-cl1-dc1 --type="merge" --patch '{"spec": { "podTemplate": { "metadata": { "annotations":{"restart":"1"}}}}}'


Park/Unpark a datacenter
________________________

You can park/unpark all pods of an Elassandra datacenter by updating the boolean ``parked`` attribute in the datacenter spec.

.. code-block:: bash

    kubectl patch elassandradatacenters elassandra-cl1-dc1 --type merge --patch '{ "spec" : { "parked" : "true"}}'

To "unpark" an Elassandra datacenter :

.. code-block:: bash

    kubectl patch elassandradatacenters elassandra-cl1-dc1 --type merge --patch '{ "spec" : { "parked" : "false"}}'

Recover from a node failure
___________________________

When a Kubernetes node is out-of-order or marked unschedulable for some reason, Elassandra pods should move to another Kubernetes node in the same availability zone.

Due to the `PodDisruptionBudged <https://kubernetes.io/docs/tasks/run-application/configure-pdb/>`_ associated to the Elassandra datacenter having
a ``maxUnavailable=1`` by default, such move won't be possible if there is an ongoing
disruption in the Elassandra datacenter. In such case, check the PDB status and react accordingly if needed:

.. code::

    kubectl get pdb elassandra-cl1-dc1 -o yaml


Recover from a disk failure
___________________________

The Elassandra operator generates Cassandra host ID in the form of XXXXXXXX-XXXX-XXX-YYYY-ZZZZZZZZZZZZ where:

* XXXXXXXX-XXXX-XXX is random to uniquely identify the Cassandra node
* YYYY is the rack index starting from 0
* ZZZZZZZZZZZZ is the pod index in the underlying rack StatefulSet.

When a node starts with an empty data disk (a new Peristent Volume), if a node with the same rack and pod index is found DEAD NORMAL in the datacenter,
the Cassandra system property -Dcassandra.replace_address_first_boot=<old_node_ip> is automatically added to replace the dead node.

So, in the case of a disk failure (lost or corrupted), you just need to delete the PV and restart the pod, as shown here for the Elassandra pod **elassandra-cl1-dc1-1-0**.

.. code::

    PVC=data-volume-elassandra-cl1-dc1-1-0
    PV=$(kubectl get pvc $PVC -o jsonpath='{.spec.volumeName}')

    kubectl patch pv $PV -p '{"metadata":{"finalizers":null}}'
    kubectl patch pvc $PVC -p '{"metadata":{"finalizers":null}}'

    kubectl delete pv $PV
    kubectl delete pvc $PVC

Once the Persistent Volume and Peristent Volume Claim are deleted, delete the pod to trigger a restart with an empty disk and recover.

.. code::

    kubectl delete pod elassandra-cl1-dc1-1-0

When the Elassandra node is restarted, you must update the elasticsearch routing right after data are streamed from the other nodes.

.. code::

    kubectl exec -it elassandra-cl1-dc1-1-0 -- bash -l -c "post _updaterouting"

Elassandra Tasks
================

The Elassandra operators adds an ElassandraTask CRD allowing to manage administration tasks on your Elassandra datacenter.
With these tasks, you can properly automate adding or removing an Elassandra datacenter from an Elassandra cluster running in one or multiple
Kubenetes clusters, and watch task status with **edctl**. Tasks are executed sequentially in a datacenter, and concurrently to datacenter reconciliations.

Repair
______

The **repair** task sequentially runs a
`nodetool repair <https://cassandra.apache.org/doc/latest/tools/nodetool/repair.html?highlight=repair>`_
on all nodes of a datacenter, with waiting by default 10s between each repair. If the keyspace is not specified,
all keyspaces are repaired.

.. code::

    cat <<EOF | kubectl apply -f -
    apiVersion: elassandra.strapdata.com/v1beta1
    kind: ElassandraTask
    metadata:
      name: cleanup-task-$$
    spec:
      cluster: "cl1"
      datacenter: "dc1"
      repair:
        waitIntervalInSec: 10
        keyspace: system_auth
    EOF

Cleanup
_______

The **cleanup** task sequentially runs a `nodetool cleanup <https://cassandra.apache.org/doc/latest/tools/nodetool/cleanup.html>`_
on all nodes of a datacenter, with waiting by default 10s between each cleanup:

* If keyspace is specified, the keyspace is removed from the datacenter ``status.needCleanupKeyspaces`` set.
* If keyspace is not specified, all keyspaces are cleaned up and the datacenter ``status.needCleanup`` is set to true
  and ``status.needCleanupKeyspaces`` is emptied.

.. code::

    cat <<EOF | kubectl apply -f -
    apiVersion: elassandra.strapdata.com/v1beta1
    kind: ElassandraTask
    metadata:
      name: cleanup-task-$$
    spec:
      cluster: "cl1"
      datacenter: "dc1"
      cleanup:
        waitIntervalInSec: 10
        keyspace: system_auth
    EOF

Replication
___________

The **replication** task adds or removes a datacenter in the Cassandra schema by updating the keyspace replication map.

The following replication task adds the datacenter dc2 in the replication maps of system keyspaces and the **foo** user keyspace.

.. code::

    cat <<EOF | kubectl apply -f -
    apiVersion: elassandra.strapdata.com/v1beta1
    kind: ElassandraTask
    metadata:
      name: replication-add-$$
      namespace: $NS
    spec:
      cluster: "cl1"
      datacenter: "dc1"
      replication:
        action: ADD
        dcName: "dc2"
        dcSize: 1
        replicationMap:
          foo: 1
    EOF

Rebuild
_______

The **rebuild** task runs a `nodetool rebuild <https://cassandra.apache.org/doc/latest/tools/nodetool/rebuild.html?highlight=rebuild>`_
on all nodes of a datacenter in order to stream the data from another existing datacenter.

The following rebuild task rebuild the datacenter **dc2** by streaming data from the datacenter **dc1**.

.. code::

    cat <<EOF | kubectl apply -f -
    apiVersion: elassandra.strapdata.com/v1beta1
    kind: ElassandraTask
    metadata:
      name: rebuild-dc2-$$
      namespace: $NS
    spec:
      cluster: "cl1"
      datacenter: "dc2"
      rebuild:
        srcDcName: "dc1"
    EOF

Update routing
______________

The **updateRouting** task updates the Elasticsearch routing table for all nodes of an Elassandra datacenter.
This is usually done after a datacenter rebuild when data becomes available to properly open elasticsearch indices.

.. code::

    cat <<EOF | kubectl apply -f -
    apiVersion: elassandra.strapdata.com/v1beta1
    kind: ElassandraTask
    metadata:
      name: updaterouting-dc2-$$
      namespace: $NS
    spec:
      cluster: "cl1"
      datacenter: "dc2"
      updateRouting: {}
    EOF

Remove nodes
____________

The **removeNodes** task runs a `nodetool removenode <https://cassandra.apache.org/doc/latest/tools/nodetool/removenode.html>`_
for all nodes of a deleted datacenter. This is usually done after a datacenter is deleted and after replication for
that datacenter has been remove with a ``replication`` task.

The following task is executed on one node of the datacenter **dc1** to remove all nodes from the datacenter **dc2**.

.. code::

    cat <<EOF | kubectl apply -f -
    apiVersion: elassandra.strapdata.com/v1beta1
    kind: ElassandraTask
    metadata:
      name: removenodes-dc2-$$
      namespace: $NS
    spec:
      cluster: "cl1"
      datacenter: "dc1"
      removeNodes:
        dcName: "dc2"
    EOF