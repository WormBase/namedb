#!/usr/bin/env perl -w

use strict;

use edn;
use Getopt::Long;

use lib 'lib';
use NameDB;

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

$species = $species || 'elegans';
$type = $type || 'CGC';

if (($type ne "CGC") && ($type ne "Sequence")) {
    die "$type is not a valid type (CGC/Sequence)";
}

my $namedb = NameDB->new(-cert => $cert, -key => $key);

open (FILE, "<$file") or die "can't open $file : $!\n";
my $count = 0;
my $txn = [];
my $objname = edn::read(':object/name');
my $changename = edn::read(':wb/change-name');
while (<FILE>) {
    my ($id, $name) = split;
    unless ($force) {
        my $valid = $namedb->get("/api/validate-gene-name?type=$type&species=$species&name=$name");
        if ($valid->{'okay'}) {
            print "Validated $name for $id.\n";
        } else {
            die $valid->{'err'};
        }
    }
        
    
    push $txn, [$changename, [$objname, $id], "Gene", $type, $name];
}

my $txr = $namedb->transact($txn);
if ($txr->{'success'}) {
    print "Done!\n";
} else {
    print "$txr->{'error'}\n";
}

