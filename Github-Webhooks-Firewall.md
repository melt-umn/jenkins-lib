
This file describes how to diagnose the CS firewall blocking Github webhooks from reaching coldpress.

## Finding logs

[The melt-umn webhook (direct link)](https://github.com/organizations/melt-umn/settings/hooks/9479568) displays a log of the most recent attempts to deliver events to Jenkins, at the bottom of the page.

If that direct link doesn't work, it's available by going to `melt-umn` organization, "Settings," "Webhooks," and then clicking on the entry for "coldpress."

If Github is displaying "Service Timeout" as the explanation under a failed event, that's likely the firewall dropping packets.

## Checking configuration

Github publishes the CIDR (IP) ranges webhooks can come from at: https://api.github.com/meta

As of 2019-07-19, the webhooks list contains:

* "192.30.252.0/22"
* "185.199.108.0/22"
* "140.82.112.0/20"

If Github's list is now different from what we display above, that's a good indication that Github has changed their webhook fleet IPs.

## Updating CS firewall

Send an email to operator (CC: evw) like this one:

> Hello,
> 
> We have firewall rules for `coldpress.cs.umn.edu` that allow Github webhooks through (to trigger Jenkins on that machine). Github appears to have changed their CIDR ranges, and so this has stopped working.
> 
> We have Github configured to hit: http://coldpress.cs.umn.edu:8080/github-webhook/
> 
> Github posts CIDR ranges for webhooks here: https://api.github.com/meta
> Which are now:
> "192.30.252.0/22"
> "185.199.108.0/22"
> "140.82.112.0/20"
> 
> coldpress's IP is: 128.101.36.173
> 
> (For reference, ticket INC2279653 was a previous time we needed them updated.)
> 
> Could someone please update the firewall configuration?
> 
> Thanks!

Also, once that's fixed and verified, update the CIDR ranges documented above in this file.

