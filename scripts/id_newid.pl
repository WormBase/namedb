#!/usr/bin/perl -w

use strict;


use edn;
use Getopt::Long;

use lib 'lib';
use NameDB;

my $USAGE = <<END;
Usage: $0 <options>
  Create a new ID from the specified domain.

Options:

  --domain       Domain of identifier to resurrect (e.g. "Gene")
  --cert         Path to certificate file.
  --key          Path to key file.
  --nameserver   Base URI of the name server to contact.

END

my ($domain, $id, $cert, $key, $ns);
GetOptions('domain:s'     => \$domain,
           'cert:s'       => \$cert,
           'key:s'        => \$key,
           'nameserver:s' => \$ns)
    or die $USAGE;

die "Must specify --domain" unless $domain;

my $namedb = NameDB->new(-cert => $cert, -key => $key);

my $tempid = edn::read('#db/id [:db.part/user -1000500]');
my $txn = [[edn::read(':wb/new-obj'),
            $domain,
            [$tempid]]];
my $txr = $namedb->transact($txn, -tempids => 1);

if ($txr->{'success'}) {
    print "Created $txr->{'tempid-report'}->[0]->[0].\n";
} else {
    print "$txr->{'error'}\n";
}
