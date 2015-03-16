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
     in the :wb namespace in order to do their actual work.  Transactions which don't match this pattern
     will be rejected.

The following functions are currently available

### :wb/new-obj 

Parameters:

      domain     name of domain (e.g. "Gene")
      ids        list of one or more tempids to create
      
If you want to create several names in a single transaction, pass multiple tempids to :wb/new-obj

### :wb/add-name

      id         object to name
      domain     domain of the object (e.g. "Gene")
      name-type  type of name to add (e.g. "Sequence_name")
      name       new name to add
      
### :wb/retract-name

      id         object to modify.
      name-type  type of name to retract
      name       name to retract
      
### :wb/merge

      id         object to survive the merge.
      idx        object to kill during merge.
      
### :wb/split

      id         object to split
      new-id     tempid to create.
      
### :wb/kill

      id         object to kill
      
### :wb/resurrect
 
      id         object to resurrect
      
### :wb/ensure-max-t

      id         object to check.
      t          basis-t for which preconditions are valid.

Check that the object `id` has not been affected by any transactions since `t`.  This ensures that any pre-conditions checked outside the Datomic transaction should still be valid.
