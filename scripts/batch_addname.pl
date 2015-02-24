#!/usr/bin/env perl -w

use strict;

use HTTP::Tiny;
use edn;
use Getopt::Long;
use Data::Dumper;

my $USAGE = <<END;
Usage: $0 <options>
  Resurrect the indicated ID.

Options:

  --file         File containing list of WBGene IDs and CGC name e.g. WBGene00008040 ttr-5
  --species      What species these are for - default = elegans
  --force        Bypass CGC name validation check.
  --cert         Path to certificate file.
  --key          Path to key file.
  --nameserver   Base URI of the name server to contact.

END

my ($file, $species, $type, $force, $cert, $ns, $key);
GetOptions('file:s'       => \$file,
           'species:s'    => \$species,
           'type:s'       => \$type,
           'force'        => \$force,
           'cert:s'       => \$cert,
           'key:s'        => \$key,
           'nameserver:s' => \$ns)
    or die "Bad opts...";

$ns = $ns || "https://dev.wormbase.org:9016";
$species = $species || 'elegans';
$type = $type || 'CGC';

if (($type ne "CGC") && ($type ne "Sequence")) {
    die "$type is not a valid type (CGC/Sequence)";
}

my $client = HTTP::Tiny->new(
    max_redirect => 0, 
    SSL_options => {
        SSL_cert_file => $cert, 
        SSL_key_file =>  $key,
        verify_hostname => 0,
        SSL_verify_mode => 0
    });

sub edn_post {
    my ($uri, $content) = @_;
    my $resp = $client->post($uri, {
        content => $content,
        headers => {
            'content-type' => 'application/edn'
        }
    });
    die "Failed to connect to nameserver $resp->{'content'}" unless $resp->{'success'};
    return edn::read($resp->{'content'});
}

open (FILE, "<$file") or die "can't open $file : $!\n";
my $count = 0;
my $txn = [];
my $objname = edn::read(':object/name');
my $changename = edn::read(':wb/change-name');
while (<FILE>) {
    my ($id, $name) = split;
    unless ($force) {
        my $resp = $client->get("$ns/api/validate-gene-name?type=$type&species=$species&name=$name");
        die "Failed to connect to nameserver $resp->{'content'}" unless $resp->{'success'};
        my $valid = edn::read($resp->{'content'});
        if ($valid->{'okay'}) {
            print "Validated $name for $id.\n";
        } else {
            die $valid->{'err'};
        }
    }
        
    
    push $txn, [$changename, [$objname, $id], "Gene", $type, $name];
}

my $txr = edn_post("$ns/api/transact", edn::write({'transaction' => $txn}));
if ($txr->{'success'}) {
    print "Done!\n";
} else {
    print "$txr->{'error'}\n";
}

