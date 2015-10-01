import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.SparkContext._
import org.apache.spark.graphx._
import org.apache.spark.rdd.RDD
import pl.appsilon.marek.sparkdatalog._

object EdgeBetweennessCommunityDetection {
  val usage =
    """  Usage: EdgeBetweennessCommunityDetection input output k_iter [--print]
         k_iter = 0 means all with maximum edge betweenness
    """.stripMargin

  def main(args: Array[String]) {

    if (args.length != 3 && args.length != 4) {
      println(usage)
      return
    }
    val K_ITER = args(2).toInt

    val conf = new SparkConf().setAppName("EdgeBetweennessCommunityDetection").setMaster("local[4]")
    val sc = new SparkContext(conf)
    sc.setCheckpointDir("somecheckpointdir")

    // Wczytuje graf
    val origin_graph = GraphLoader.edgeListFile(sc, args(0), canonicalOrientation = true)

    var prev_graph = Graph(origin_graph.vertices, origin_graph.edges)
    // znajduje listy sąsiedztwa
    var graph = Graph(origin_graph.collectNeighborIds(EdgeDirection.Either), origin_graph.edges)

    // i liczbę krawędzi
    val num_edges = graph.edges.count() * 2
    var curr_modularity: Double = 0.0
    var last_modularity: Double = 0.0

    // Dopóki wskaźnik modularności się nie pogorsza to iteruje...
    do {
      last_modularity = curr_modularity

      // StageI - initialization (z papera bigcomp14.pdf)
      // TODO: Prepare edges as triples (src, dst, len).
      val rawEdges: RDD[(Int, Int, Int)] = ???
      val paths: RDD[(Int, Int, Int)] = findAllPaths(sc, rawEdges) // Result is (src, dst, distance)

      // StageII & III
      //TODO: Compute this based on `paths`
      val edges: RDD[((VertexId, VertexId), Double)] = ???  /* paths.flatMap(e => e._2.edges()).reduceByKey(_ + _).sortBy(e => e._2, false) */

      val toRemove = if (K_ITER == 0) {
        val maxValue = edges.first()._2
        edges.filter(e => e._2 >= maxValue).map(e => e._1).collect()
      } else {
        edges.map(e => e._1).take(K_ITER * 2)
      }
      toRemove.foreach(e => if (e._1 < e._2) println("Removed edge " + e))

      // ,,StageIV'' - tworze graf bez usunietych krawedzi
      val ngraph = graph.subgraph { e =>
        val curr_edge = (e.srcId, e.dstId)
        !(toRemove.contains(curr_edge))
      }
      val nvertices = ngraph.collectNeighborIds(EdgeDirection.Either)

      prev_graph = Graph(origin_graph.vertices, graph.edges)
      graph = Graph(nvertices, ngraph.edges)

      // Modularity
      // tworze graf z numerami spójnych składowych przypisanymi do wierzchołków i oryginalnymi krawędziami
      // po czym zliczam wskaźnik modularności tak jak w socialite i paperze SocCom-metric.13.pdf
      curr_modularity = Graph(graph.connectedComponents().vertices, origin_graph.edges).triplets.flatMap { e =>
        if (e.srcAttr == e.dstAttr) {
          Array((e.srcAttr, (1, 0)), (e.dstAttr, (1, 0)))
        } else {
          Array((e.srcAttr, (0, 1)), (e.dstAttr, (0, 1)))
        }
      }.reduceByKey((a, b) => (a._1 + b._1, a._2 + b._2)).map { e =>
        val e_in = e._2._1
        val e_out = e._2._2
        val tmp = (2.0 * e_in + 1.0 * e_out) / (2.0 * num_edges)
        e_in / (1.0 * num_edges) - tmp * tmp
      }.reduce(_+_)
      println(curr_modularity)

    } while (curr_modularity >= last_modularity)

    val connected_components = prev_graph.connectedComponents().vertices.map(e => (e._2, e._1))
      .aggregateByKey(Nil:List[VertexId])((a, b) => b :: a, (a, b) => a ++ b)

    connected_components.values.saveAsTextFile(args(1))
    if (args.length == 4 && args(3)=="--print") {
      connected_components.values.collect.foreach(println(_))
    }
  }

  def findAllPaths(sc: SparkContext, edges: RDD[(Int, Int, Int)]): RDD[(Int, Int, Int)] = {
    val database = Database(Relation.ternary("Edges", edges))
    val query = """
                  |declare Path(int s, int d, int l).
                  |Path(s, d, l) :- Edge(s, d, l).
                  |Path(s, d, l) :- Path(s, v, l1), Edge(v, d, l2), l = l1+l2.
                """.stripMargin
    val resultDatabase = database.datalog(query)
    val resultsRdd: RDD[Fact] = resultDatabase("Path")
    //println("Computed %d results.".format(resultsRdd.count()))
    resultsRdd.map(fact => (fact(0), fact(1), fact(2)))
  }
}

// Ta klasa reprezentuje Tuple z papera bigcomp14.pdf
class PathInfo(_src: VertexId, _dest: VertexId, _dist: Int = 0, _active: Boolean = true, _path: List[VertexId] = Nil,
               _adjList: Array[VertexId] = Array(), _weight: Int = 1) extends Serializable{
  var src: VertexId = _src
  var dest: VertexId = _dest
  var dist: Int = _dist
  var active: Boolean = _active
  var path: List[VertexId] = _path
  var adjList: Array[VertexId] = _adjList
  var weight: Int = _weight

  def relax(): List[PathInfo] = {
    if (!active) return this :: Nil
    val newPath: List[VertexId] = dest :: this.path
    var ret = new PathInfo(src, dest, dist + 1, false, newPath, adjList, weight) :: Nil
    for (v <- adjList)
      ret = new PathInfo(src, v, dist + 1, true, newPath, Array(), weight) :: ret
    return ret
  }

  def edges() = {
    for (List(s, t) <- path.sliding(2))
      yield ((s, t), 1.0 / weight)
  }

  def updateAdjList(adjListc: Array[VertexId]) = new PathInfo(src, dest, dist, active, path, adjListc, weight)

  override def toString(): String =
    "(" + src + ", " + dest + ", " + dist + ", " + active + ", " + weight + ", " + path + ", [" + adjList.mkString(",") + "])"

}
