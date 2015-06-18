__This software is ALPHA.__

# HAP Browser

[![Build Status](https://travis-ci.org/alexanderkiel/hap-browser.svg?branch=master)](https://travis-ci.org/alexanderkiel/hap-browser)

Generic Hypermedia Application Protocol (HAP) UI.

## Build

Currently a complete compilation including a production optimized ClojureScript
to JavaScript compilation works using:

    lein with-profile production compile :all

## Run HAP Browser with Docker

There is an automated build of HAP Browser on [Docker Hub][3] which builds a
container running a [Nginx][2] web server which delivers the static files of the
HAP Browser web application and proxies the backend services.

The container exposes port 80.

Start the container with the following command

    docker run -p 80:80 akiel/hap-browser

## Develop

HAP Browser uses [figwheel][1]

    rlwrap lein figwheel

Currently I get many warnings if I start figwheel without doing a `lein clean` 
first. 

### Frontend State Management and DOM Update

TODO: React, Om

## License

Copyright © 2015 Alexander Kiel

Distributed under the Eclipse Public License, the same as Clojure.

[1]: <https://github.com/bhauman/lein-figwheel>
[2]: <http://nginx.org/>
[3]: <https://registry.hub.docker.com/u/akiel/hap-browser/>
