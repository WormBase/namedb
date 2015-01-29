(ns wb.name.utils)

(defn those
  "Return a seq consisting (only) of the true arguments,
   or `nil` if no arguments are true"
  [& args]
  (seq (filter identity args)))

(defn vmap
  "Construct a map from alternating key-value pairs, discarding any keys
  associated with nil values."
  [& args] 
  (into {} (for [[k v] (partition 2 args) 
                 :when (not (nil? v))] 
             [k v])))
