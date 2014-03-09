mapmailer [![Build Status](https://travis-ci.org/analytically/mapmailer.png)](https://travis-ci.org/analytically/mapmailer)
=========

Email CRM contacts by drawing (polygon and circle) an area on a map. Built using [Play Framework 2.2](http://www.playframework.org) (Scala).
Follow [@analytically](http://twitter.com/analytically) for updates.

![screenshot](screenshot.png)
![screenshot2](screenshot2.png)

Works with:
  - [Capsule CRM](http://www.capsulecrm.com/)

#### Requirements

Java 6 or later. [MongoDB](http://www.mongodb.org) at localhost:27017. A Capsule CRM account and token.

#### Building (optional)

Requires [Play Framework 2.2](http://www.playframework.com/).

```
play assembly
```

This builds a single, executable 'fat' jar in `target/scala-2.10`.

#### Running

Prebuilt releases are available [here](https://github.com/analytically/mapmailer/releases).

Requires [Java 7](http://java.com/en/download/index.jsp). Capsule CRM users can find their API token by visiting
`My Preferences` via their username menu in the Capsule navigation bar. See [application.conf](conf/application.conf)
for more configurable options.

Copy the [CodePoint Open CSV](https://www.ordnancesurvey.co.uk/opendatadownload/products.html) (scroll halfway down, 20mb)
files to the `codepointopen` directory in the same directory with the downloaded jar.

The start the application:

```
java -Dcapsulecrm.url=https://example.capsulecrm.com -Dcapsulecrm.token=abcdef123456789 -jar mapmailer.jar
```

After all CodePoint Open files are imported, they are moved to the `codepointopen/done` directory.

Then visit [http://localhost:9000](http://localhost:9000) and you should see the map.

#### Technology

* [Play Framework 2.2.1](http://www.playframework.org), as web framework
* [Apache Camel](http://camel.apache.org) to [process and monitor](https://github.com/analytically/mapmailer/blob/master/app/Global.scala#L34) the `codepointopen` directory and to tell the actors about the postcodes
* [Akka](http://akka.io) provides a nice concurrency model [to process the 1.7 million postcodes](https://github.com/analytically/mapmailer/blob/master/app/actors/actors.scala#L41) in under one minute on modern hardware
* [GeoTools](http://www.geotools.org) converts the eastings/northings to latitude/longitude
* [ReactiveMongo](http://reactivemongo.org/) is a scala MongoDB driver that provides fully non-blocking and asynchronous I/O operations
* [MongoDB](http://www.mongodb.org) as database with two-dimensional geospatial indexes (see [Geospatial Indexing](http://www.mongodb.org/display/DOCS/Geospatial+Indexing))
* [Leaflet](http://leafletjs.com/) for the map
* [Leaflet Draw](https://github.com/Leaflet/Leaflet.draw)
* [Thunderforest](http://www.thunderforest.com/) transport map
* [Bootstrap](http://getbootstrap.com/) and [Font Awesome](http://fortawesome.github.com/Font-Awesome/)

#### Background and usecase

This software was built for [Coen Recruitment](http://www.coen.co.uk/), an education recruitment agency in the UK. Since
they prioritise on location and endeavour to find teachers work close to home, their consultants need map area selection
to market teachers to schools efficiently. Parts of this project are based on [CamelCode](https://github.com/analytically/camelcode).

#### License

Licensed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0).

Copyright 2014 [Mathias Bogaert](mailto:mathias.bogaert@gmail.com).