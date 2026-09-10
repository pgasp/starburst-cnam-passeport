FROM harbor.starburstdata.net/starburstdata/starburst-enterprise:482.0.0
USER root
RUN mkdir -p /usr/lib/starburst/plugin/cnam-passeport
COPY target/passeport-group-provider-1.0-SNAPSHOT.jar /usr/lib/starburst/plugin/cnam-passeport/
USER starburst
