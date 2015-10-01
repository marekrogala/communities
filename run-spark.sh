#!/bin/bash
if [ $# -lt 3 ] ; then
  echo -e "Usage: run-spark.py INPUT OUTPUT K_ITER"
  exit 1
fi

sbt package

INPUT=$1
OUTPUT=$2
K_ITER=$3

spark-submit --class EdgeBetweennessCommunityDetection \
    target/scala-2.10/community-detection_2.10-1.0.jar \
    $INPUT $OUTPUT $K_ITER \
    --print \
    2>>logs/spark
