__This software is ALPHA.__

# HAP Browser

[![Build Status](https://travis-ci.org/alexanderkiel/hap-browser.svg?branch=master)](https://travis-ci.org/alexanderkiel/hap-browser)

Generic [Hypermedia Application Protocol][4] (HAP) UI.

The Hypermedia Application Protocol is self-describing, opening the possibility
for fully generic UI's suiting humans. With HAP Browser you can discover and 
manipulate your API without the need to install anything additionally to your
API. The only thing your API needs are the following [CORS][5] headers because
HAP Browser is a Web App accessing your API on a different origin.

CORS Headers on OPTIONS requests:

    Access-Control-Allow-Origin: *
    Access-Control-Allow-Methods: GET, POST, PUT, DELETE
    Access-Control-Allow-Headers: Accept, If-Match, Content-Type
    
CORS Headers on all other requests:

    Access-Control-Allow-Origin: *
    Access-Control-Expose-Headers: ETag, Location
    
## Hosted HAP Browser

You can access a hosted version of HAP Browser here:

    http://hap-browser.alexanderkiel.net
    
I'll try to keep it running and current. But it's not guarantied. Please ping me
on Twitter `@alexander_kiel` if there are any issues.

There is also an example application called [HAP ToDo][6] available. Just put
    
    http://hap-todo.alexanderkiel.net
    
into the address bar of HAP Browser.

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

    docker run -d --name hap-browser -p 8080:80 akiel/hap-browser

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
[4]: <https://github.com/alexanderkiel/hap-spec>
[5]: <https://developer.mozilla.org/en-US/docs/Web/HTTP/Access_control_CORS>
[6]: <https://github.com/alexanderkiel/hap-todo>
