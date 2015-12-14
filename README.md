# Tide

Tide is useful for migrating and comparing AWS resources. It also has a task system that allows for resilient long running tasks. It may also be useful for avoiding AWS throttling as a cache. 

# Features

## Migration

#### Auto Scaling Groups
Tide is capable of migrating an Auto Scaling Group to another location (account, region, and VPC). It performs a "deep copy" meaning that it will also copy attached launch configurations, security groups and load balancers if they don't already exist in the new location. It can handle cyclical references and any necessary naming changes (load balancers must have unique names across all VPCs in a region). Nothing will be deleted or modified, just new resources created. New ASGs will be disabled (not taking traffic) with zero instances. There is a "dry run" ability to show what will be created and why.

#### Security Groups and Load Balancers
It is possible to migrate these without being attached to an ASG.

#### Pipelines
Tide can migrate a pipeline to another VPC. Copying a pipeline to another region or account raises some questions.

## Comparisons
Tide can show differences of attributes across multiple AWS resources. It can be useful to see a diff of all security groups with the same name in an account for example.

## Tasks
Akka clustering and persistence are used to make the async task system resilient. Note that tide creates an Akka "cluster" from instances found in the same Spinnaker "cluster". Tasks can move between instances in the cluster. Tasks will also restart if all instances are terminated and then launched again.

New task types are easy to implement and harness task system functionality such as the task lifecycle, subtasks, parallel execution, and logging. Migrations are a type of task. There is also a task type that can continuously attach classicLink to running instances that need it.

# Usage
See the swagger docs for usage:
http://host/swagger-ui.html

# Dependencies

* Redis is used by Akka to persist events.
* Edda (or AWS directly) is used for read operations.
* [CloudDriver](https://github.com/spinnaker/clouddriver) is used for write operations.

# Configuration
Tide is [configured like all other Spinnaker services](http://spinnaker.io/documentation/properties.html).

AWS account [config is handled just like CloudDriver](https://github.com/spinnaker/clouddriver/tree/master/clouddriver-aws).

You can find an [example config file](https://github.com/spinnaker/tide/blob/master/tide-web/config/tide.yml) in the codebase. Note that you will need to add specifics for the dependencies mentioned above and AWS account specifics.

# Running
```./gradlew bootrun```
