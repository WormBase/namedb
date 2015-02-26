package NameDB;

use warnings;
use strict;

use HTTP::Tiny;
use edn;

sub new {
    my $class = shift;
    my $args = { @_ };
    my $self = {
        ns => $args->{'-ns'} || 'https://dev.wormbase.org:9016',
        cert => $args->{'-cert'},
        key => $args->{'-key'}
    };
    die "Undefined NameDB URI." unless $self->{'ns'};
    die "Undefined certificate (-cert)." unless $self->{'cert'};
    die "Undefined key (-key)." unless $self->{'key'};

    $self->{'client'} = HTTP::Tiny->new(
        max_redirect => 0,
        SSL_options => {
            SSL_cert_file => $self->{'cert'},
            SSL_key_file => $self->{'key'}
        });

    bless $self, $class;
}

sub _edn_post {
    my ($self, $uri, $content) = @_;
    my $resp = $self->{'client'}->post($uri, {
        content => $content,
        headers => {
            'content-type' => 'application/edn'
        }
    });
    die "Failed to connect to nameserver $resp->{'content'}" unless $resp->{'success'};
    return edn::read($resp->{'content'});
}

sub query {
    my $self = shift;
    my $q = shift;
    my $params = [ @_ ];
    
    $q = EDN::Literal->new($q) unless ref($q);

    my $post = {
        'query' => $q
    };
    if (scalar(@$params) > 0) {
        $post->{'params'} = $params;
    };

    $self->_edn_post(
        "$self->{'ns'}/api/query", 
        edn::write($post)
    );
}

sub transact {
    my $self = shift;
    my $txn = shift;
    my $opts = { @_ };
    
    $txn = EDN::Literal->new($txn) unless ref($txn);
    my $post = {
        'transaction' => $txn
    };
    if ($opts->{'-tempids'}) {
        $post->{'tempid-report'} = edn::read('true');
    };

    $self->_edn_post(
        "$self->{'ns'}/api/transact",
        edn::write($post)
    );
}

sub get {
    my ($self, $url) = @_;
    my $resp = $self->{'client'}->get("$self->{'ns'}$url");
    die "Failed to connect to nameserver $resp->{'content'}" unless $resp->{'success'};
    edn::read($resp->{'content'});
}

1;
