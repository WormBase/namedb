#!/usr/bin/perl -w

use strict;

use HTTP::Tiny;
use edn;
use Getopt::Long;
use Data::Dumper;

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
    or die "Bad opts...";

$ns = $ns || "https://dev.wormbase.org:9016";

my $client = HTTP::Tiny->new(
    max_redirect => 0, 
    SSL_options => {
        SSL_cert_file => $cert, 
        SSL_key_file =>  $key
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

my $query = <<END;
  {:query [:find ?id ?live
           :in \$ ?id
           :where [?obj :object/name ?id]
                  [?obj :object/live ?live]]
    :params ["$id"]}
END

my $result = edn_post("$ns/api/query", $query);
my $count = scalar @{$result};

die "Could not find identifier $id." unless $count > 0;
die "Ambiguous identifier $id." unless $count == 1;

my ($cid, $live) = @{$result->[0]};

die "$id is Still Alive.\n" if $live;

my $txn = edn::write(
    {transaction => [[edn::read(':wb/resurrect'), 
                      [edn::read(':object/name'), $cid]]]
    });
my $txr = edn_post("$ns/api/transact", $txn);
if ($txr->{'success'}) {
    print "$cid resurrected.\n"
} else {
    print "Resurrection failed: $txr->{'error'}\n";
}
