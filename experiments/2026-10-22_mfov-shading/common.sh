# shared settings for the mfov-shading experiment scripts
BASE_URL=http://em-services-1.int.janelia.org:8080/render-ws/v1
OWNER=hess_wafers_60_61
PROJECT=test_ic2d
SOURCE_STACK=${SOURCE_STACK:-w61_s109_r00_gc_z1}
THREADS=36
EXP_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
JAR=$EXP_DIR/../../render-ws-java-client/target/render-ws-java-client-5.0.0-SNAPSHOT-standalone.jar
JAVA="java -Xmx64g -Djava.io.tmpdir=${TMPDIR:-/tmp} -cp $JAR"
