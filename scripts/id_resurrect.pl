#!/usr/bin/perl -w

use strict;

use edn;
use Getopt::Long;

use lib 'lib';
use NameDB;

my $USAGE = <<END;
Usage: $0 <options>
  Resurrect the indicated ID.

Options:

  --domain       Domain of identifier to resurrect (e.g. "Gene")
  --id           Identifier to resurrect.
  --cert         Path to certificate file.
  --key          Path to key file.
  --nameserver   Base URI of the name server to contact.

END

my ($domain, $id, $cert, $key, $ns);
GetOptions('domain:s'     => \$domain,
           'id:s'         => \$id,
           'cert:s'       => \$cert,
           'key:s'        => \$key,
           'nameserver:s' => \$ns)
    or die $USAGE;

my $namedb = NameDB->new(-cert => $cert, -key => $key);

my $query = <<END;
  [:find ?id ?live
   :in \$ ?id
   :where [?obj :object/name ?id]
          [?obj :object/live ?live]]
END

my $result = $namedb->query($query, $id);
my $count = scalar @{$result};

die "Could not find identifier $id." unless $count > 0;
die "Ambiguous identifier $id." unless $count == 1;

my ($cid, $live) = @{$result->[0]};

die "$id is Still Alive.\n" if $live;

my $txn = [[edn::read(':wb/resurrect'), 
            [edn::read(':object/name'), $cid]]];
my $txr = $namedb->transact($txn);
if ($txr->{'success'}) {
    print "$cid resurrected.\n"
} else {
    print "Resurrection failed: $txr->{'error'}\n";
}
