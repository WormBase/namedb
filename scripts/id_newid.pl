#!/usr/bin/perl -w

use strict;

use HTTP::Tiny;
use edn;
use Getopt::Long;
use Data::Dumper;

my $USAGE = <<END;
Usage: $0 <options>
  Create a new ID from the specified domain.

Options:

  --domain       Domain of identifier to resurrect (e.g. "Gene")
  --cert         Path to certificate file.
  --nameserver   Base URI of the name server to contact.

END

my ($domain, $id, $cert, $ns);
GetOptions('domain:s'     => \$domain,
           'cert:s'       => \$cert,
           'nameserver:s' => \$ns)
    or die $USAGE;

$ns = $ns || "https://db.wormbase.org:8131";

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

my $tempid = edn::read('#db/id [:db.part/user -1000500]');
my $txn = edn::write(
    {'transaction' => [[edn::read(':wb/new-obj'),
                      $domain,
                      [$tempid]]],
     'tempid-report' => edn::read('true')
    });
my $txr = edn_post("$ns/api/transact", $txn);

if ($txr->{'success'}) {
    print "Created $txr->{'tempid-report'}->[0]->[0].\n";
} else {
    print "$txr->{'error'}\n";
}
