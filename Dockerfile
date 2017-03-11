FROM cogniteev/oracle-java

MAINTAINER Petr Viktorovich Fedchenkov <giggsoff@gmail.com>

RUN apt update && wget https://github.com/AaltoAsia/O-MI/releases/download/0.8.1/O-MI-Node_0.8.1_all.deb

RUN dpkg -i O-MI-Node_0.8.1_all.deb

RUN mkdir /conf

COPY application.conf /conf/application.conf

EXPOSE 8180 8080
#ENTRYPOINT service cygnus start && tail -f /var/log/cygnus/cygnus.log
ENTRYPOINT o-mi-node

