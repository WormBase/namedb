API for scripting access to the name server
==========================

All access to the name server is intended to go via the HTTPS interface.  There are two
endpoints for programatic access:

      /api/query
      /api/transact

Both endpoints expect HTTP POSTs, and both expect and produce [EDN](https://github.com/edn-format/edn) data.  There's an incomplete list of EDN libraries [here](https://github.com/edn-format/edn/wiki/Implementations).

This description assumes some familiarity with [Datomic](http://www.datomic.com/) and its Datalog query language.

## Query access

A query consists of an EDN map with a `:query` property holding a Datomic query (encoded as EDN), and (optionally) 
a `:params` property holding a list of parameters to pass to the query *in addition* to the current nameserver database,
which is always passed as a first query paramter.  So, to fetch all the primary names in a domain:

    {:query [:find ?name 
             :where [?obj :object/domain [:domain/name "Gene"]]
                    [?obj :object/name ?name]]}
                    
...or to find the secondary names associated with a given primary name (passed in via a parameter):

     {:query [:find ?type ?name
              :in $ ?primary
              :where [?obj :object/name ?primary]
                     [?obj :object/secondary ?sec]
                     [?sec :object.secondary/name-type ?nt]
                     [?nt  :name-type/name ?type]
                     [?sec :object.secondary/name ?name]]
      :params ["WBGene00000016"]}


## Transaction processing (updates).

The transact endpoint also provides relatively direct access to the underlying Datomic database.  However,
there are two caveats:

   - All transactions are stamped with some metadata based on the authentication details provided when
     submitting the transaction.
   - To ensure the database stays in a consistent state, all transactions must call transaction functions 
     in the :wb namespace in order to do their actual work.

