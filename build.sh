#!/bin/bash

if [ ! -z $1 ] 
then 
    # got input
    sed -i.bak 's|<elasticsearch.version>.*</elasticsearch.version>|<elasticsearch.version>'"${1}"'</elasticsearch.version>|g'  pom.xml && rm pom.xml.bak
fi

PLUGIN_VERSION=`grep '<!-- plugin_version' pom.xml | sed 's|</b>|-|g' | sed 's|<[^>]*>||g' |xargs`
ES_VERSION=`grep '<elasticsearch.version' pom.xml | sed 's|</b>|-|g' | sed 's|<[^>]*>||g' |xargs`

sed  -i.bak  "s/^\(elasticsearch.version=\).*/\1$ES_VERSION/" src/main/resources/plugin-descriptor.properties && rm src/main/resources/plugin-descriptor.properties.bak
sed  -i.bak  "s/^\(version=\).*/\1$PLUGIN_VERSION/" src/main/resources/plugin-descriptor.properties && rm src/main/resources/plugin-descriptor.properties.bak

sed  -i.bak  "s/^\(elasticsearch.version=\).*/\1$ES_VERSION/" src/test/eshome/plugins/readonlyrest/plugin-descriptor.properties && rm src/test/eshome/plugins/readonlyrest/plugin-descriptor.properties.bak
sed  -i.bak  "s/^\(version=\).*/\1$PLUGIN_VERSION/" src/test/eshome/plugins/readonlyrest/plugin-descriptor.properties && rm src/test/eshome/plugins/readonlyrest/plugin-descriptor.properties.bak

PACK=`ls target/elasticsearch-readonlyrest-v*es-v2*.zip`

# -Dmaven.test.skip=true -DskipTests
mvn clean package || exit 1


zip -j -g $PACK src/main/resources/plugin-descriptor.properties
zip -j -g $PACK src/main/resources/plugin-security.policy

shasum -a1 $PACK | awk {'print $1'} > $PACK.sha1
