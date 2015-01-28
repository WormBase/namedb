(ns wb.name.utils)

(defn those
  "Return a seq consisting (only) of the true arguments,
   or `nil` if no arguments are true"
  [& args]
  (seq (filter identity args)))
