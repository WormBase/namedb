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

  --domain   Domain of identifier to resurrect (e.g. "Gene")
  --id       Identifier to resurrect.
  --cert     Path to certificate file.

END

my ($domain, $id, $cert);
GetOptions('domain:s'     => \$domain,
           'id:s'         => \$id,
           'cert:s'       => \$cert)
    or die "Bad opts...";

my $client = HTTP::Tiny->new(
    max_redirect => 0, 
    SSL_options => {
        SSL_cert_file => $cert, 
        SSL_key_file =>  $cert
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

my $result = edn_post('https://db.wormbase.org:8131/api/query', $query);
my $count = scalar @{$result};

die "Could not find identifier $id." unless $count > 0;
die "Ambiguous identifier $id." unless $count == 1;

my ($cid, $live) = @{$result->[0]};

die "$id is Still Alive.\n" if $live;

my $txn = edn::write(
    {transaction => [[edn::read(':db/add'), 
                      [edn::read(':object/name'), $cid], 
                      edn::read(':object/live'), 
                      edn::read('true')]]
    });
my $txr = edn_post('https://db.wormbase.org:8131/api/transact', $txn);
if ($txr->{'success'}) {
    print "$cid resurrected.\n"
} else {
    print "Resurrection failed: $txr->{'error'}\n";
}
