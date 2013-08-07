#!/bin/sh

git archive --format=tar --prefix=play-1.2.6-1.2.6/ HEAD | gzip > ../play-1.2.6-1.2.6.tar.gz
