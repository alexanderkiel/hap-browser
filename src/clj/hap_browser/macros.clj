(ns hap-browser.macros)

(defmacro h
  "Handler for :on-click and others. Calls .preventDefault on event."
  [& body]
  `(fn [e#] ~@body (.preventDefault e#)))
