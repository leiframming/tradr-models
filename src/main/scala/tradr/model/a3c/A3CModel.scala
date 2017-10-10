package tradr.model.a3c

import java.io.File
import java.net.InetSocketAddress

import com.datastax.oss.driver.api.core.{Cluster, CqlIdentifier}
import com.datastax.oss.driver.internal.core.config.typesafe.DefaultDriverConfigLoader
import com.typesafe.config.Config
import org.deeplearning4j.nn.conf.inputs.InputType
import org.deeplearning4j.nn.conf.layers.{ConvolutionLayer, DenseLayer}
import org.deeplearning4j.nn.conf.{ComputationGraphConfiguration, NeuralNetConfiguration}
import org.deeplearning4j.nn.gradient.DefaultGradient
import org.deeplearning4j.nn.graph.ComputationGraph
import org.deeplearning4j.nn.weights.WeightInit
import org.deeplearning4j.util.ModelSerializer
import org.nd4j.linalg.activations.Activation
import org.nd4j.linalg.api.ndarray.INDArray
import org.nd4j.linalg.factory.Nd4j
import tradr.common.PricingPoint
import tradr.common.models.Model
import tradr.common.trading.{Action, Instruments, Trade}


object A3CModel {

  /**
    * Load a network from disk
    * @param id ID of the model that is loaded
    * @param conf Config, has the save file location stored as "tradr.predictor.network.saveFile"
    * @return
    */
  def load(id: String, conf: Config): A3CModel = {
    val saveFile: String = conf.getString("tradr.predictor.modelFolder") + id

    println(s"Loading a3c network from $saveFile")
    val f = new File(saveFile)
    if (f.exists()) {
      val network = ModelSerializer.restoreComputationGraph(saveFile)
      network.init()
      A3CModel(id, network)
    } else {
      create(id, conf)
    }
  }

  def create(id: String, conf: Config): A3CModel = {

    val graphConf = getComputationGraph(conf)
    val net = new ComputationGraph(graphConf)
    net.init()
    A3CModel(id, net)
  }

  def save(model: A3CModel, conf: Config, id: String): Unit = {

    val saveFile: String = conf.getString("tradr.predictor.modelFolder") + id

    ModelSerializer.writeModel(
      model.network,
      saveFile,
      true
    )
  }
  /**
    * Create a computation graph in order to get a new network.
    * Depends on "tradr.predictor.a3c.inputsize" configuration
    * @param conf
    * @return
    */
  def getComputationGraph(conf: Config): ComputationGraphConfiguration = {
    val inputSize = conf.getInt("tradr.predictor.a3c.inputsize")

    new NeuralNetConfiguration.Builder()
      .seed(123)
      .graphBuilder()
      .addInputs("frame")
      .addLayer(
        "layer1",
        new ConvolutionLayer
        .Builder(1, 5)
          .weightInit(WeightInit.XAVIER)
          .nIn(1)
          .stride(5, 1)
          .nOut(20)
          .activation(Activation.RELU)
          .build(),
        "frame")
      .addLayer(
        "layer2",
        new ConvolutionLayer
        .Builder(1, 5)
          .nIn(20)
          .weightInit(WeightInit.XAVIER)
          .stride(1, 2)
          .nOut(20)
          .activation(Activation.RELU)
          .build(),
        "layer1")
      .addLayer("fc",
        new DenseLayer
        .Builder()
          .weightInit(WeightInit.XAVIER)
          .activation(Activation.RELU)
          .nIn(1200)
          .nOut(100)
          .build(),
        "layer2")
      .addLayer(
        "actionProbabilities",
        new DenseLayer.Builder()
          .nIn(100)
          .weightInit(WeightInit.XAVIER)
          .nOut(4)
          .activation(Activation.SOFTMAX)
          .build(),
        "fc")
      .addLayer(
        "valueFunction",
        new DenseLayer.Builder()
          .weightInit(WeightInit.XAVIER)
          .nIn(100)
          .nOut(1)
          .activation(Activation.IDENTITY)
          .build(),
        "fc")
      .setOutputs("actionProbabilities", "valueFunction")
      .setInputTypes(InputType.convolutionalFlat(1, inputSize, 1))
      .build()
  }


  private[this] def getCassandraCluster(conf: Config): Cluster = {

    val cassandraIp = conf.getString("cassandra.ip")
    val cassandraPort = conf.getString("cassandra.connectorPort").toInt
    println(s"Connecting to cassandra at $cassandraIp:$cassandraPort")

    val inetSocketAddress = InetSocketAddress.createUnresolved(cassandraIp, cassandraPort)
    val defaultConfig = new DefaultDriverConfigLoader()
    Cluster
      .builder()
      .addContactPoint(inetSocketAddress)
      .build()
  }


  /**
    * Request Cassandra to get all the data needed for a prediction
    * @param conf
    * @return
    */
  private[this] def getRates(from: Long, to: Long,
               instrument: Instruments.Value, conf: Config): Seq[PricingPoint] = {
    val cassandra = getCassandraCluster(conf)

    val keyspace = conf.getString("cassandra.keyspace")
    val tablename = conf.getString("cassandra.currencyTable")

    val cqlKeyspace = CqlIdentifier.fromInternal(s"$keyspace")
    val session = cassandra.connect(cqlKeyspace)

    // Maybe async execution? However, we have no batch, only one statement to execute
    // and we can't do anything without the data so it might as well be this simple request
    val resultSet = session.execute(
      s"SELECT * from $keyspace.$tablename " +
        s"WHERE instrument = '${instrument.toString}' " +
        s"""AND "timestamp < $to" """+
        s"AND timestamp >= $from"
    )

    var results = Seq[PricingPoint]()
    val it = resultSet.iterator()
    while (it.hasNext) {
      val row = it.next
      val point = PricingPoint(
        timestamp = row.getLong(0),
        currencyPair = row.getString(1),
        value = row.getDouble(2))
      results = results :+ point
    }

    results
  }




  /**
    * Convert the data from cassandra into a (multidimensional) frame
    * We look at a certain time window in cassandra and compute a fixed set of input
    * pixels for the NN. In this very first version, we will just take the mean of pixels
    * within a distinct bin.
    * I.e.: Bin the data, compute Mean of PricingPoints and return as Array
    *
    * If we do not have enough data to fill the array we need to throw an error
    */
  private[this] def convertToFrame(pricingPoints: Seq[PricingPoint],
                     inputSize: Int,
                     start: Long, end: Long): Array[Double] = {

    val stepSize = (end - start)/inputSize
    val range = start until end by stepSize

    range
      .indices
      .map(i => {
        val filteredSet = pricingPoints
          .filter(point => point.timestamp > range(i) && point.timestamp <= range(i+1))
          .map(_.value)
        assert(filteredSet.nonEmpty)
        filteredSet.sum / filteredSet.size.toDouble
      })
      .toArray
  }


  def getFrame(now: Long, conf: Config): Array[Double] = {
    val inputSize = conf.getInt("tradr.predictor.frameSize")
    val prev = now - (1000L * 60 * 60)
    val pricingPoints = getRates(now, prev, Instruments.EURUSD, conf)
    convertToFrame(pricingPoints, inputSize, prev, now)
  }


  private[this] def getPredictions(from: Long, to: Long, modelName: String, conf: Config) = {
    val cassandra = getCassandraCluster(conf)

    val keyspace = conf.getString("cassandra.keyspace")
    val tablename = conf.getString("cassandra.predictionTable")

    val cqlKeyspace = CqlIdentifier.fromInternal(s"$keyspace")
    val session = cassandra.connect(cqlKeyspace)

    // Maybe async execution? However, we have no batch, only one statement to execute
    // and we can't do anything without the data so it might as well be this simple request
    val resultSet = session.execute(
      s"SELECT * from $keyspace.$tablename " +
        s"WHERE model = '$modelName' " +
        s"""AND "timestamp < $to" """+
        s"AND timestamp >= $from"
    )

    var results = Seq[A3CPredictionResult]()
    val it = resultSet.iterator()
    while (it.hasNext) {
      val row = it.next
      val prediction = A3CPredictionResult(
        model = row.getString(0),
        timestamp = row.getLong(1),
        actionProbabilities = row.get[Array[Double]](2, classOf[Array[Double]]),
        valuePrediction = row.getDouble(3)
      )


      results = results :+ prediction
    }

    results
  }


//  def padTradesWithHold(trade: Trade, config: Config) = {
//    val tradeInterval = config.getInt("tradr.trader.interval")
//
//
//    trade
//      .tradeSequence
//      .indices
//      .drop(1)
//      .foldLeft(Seq(trade.tradeSequence.head)){
//        case (seq, i) => {
//
//          // Get the time diff in seconds. For each second that no trade occurred we assume
//          // The proposed action was a HOLD
//          val start = trade.tradeSequence(i-1).time + tradeInterval
//          val end = trade.tradeSequence(i).time
//
//          val holdTrades = (start until end by tradeInterval).map(
//            t => trade.tradeSequence.head.copy(
//              id = t,
//              action = Action.Hold,
//              time = t,
//              portfolioChange = trade.tradeSequence.head.portfolioChange.mapValues(_ => 0.0)
//            )
//          )
//
//          seq ++ holdTrades ++ Array(trade.tradeSequence(i))
//
//        }
//      }
//  }


//  /**
//    * Compute the gradient map (gradient for each variable) from the trade.
//    * We do this explicitely so that save one forward pass
//    * @param network
//    * @param trade
//    * @param gamma
//    * @param profit
//    * @return
//    */
//  def computeGradientMap(network: ComputationGraph,
//                         trade: Trade,
//                         gamma: Double,
//                         profit: Double): mutable.Map[String, INDArray] = {
//    val r = profit/trade.tradeSequence.size
//    var initR = trade.tradeSequence.last.valuePrediction.head
//    val initialGradient: collection.mutable.Map[String, INDArray] = collection.mutable.Map()
//
//    val totalGradient = trade
//      .tradeSequence
//      .indices
//      .reverse
//      .drop(1)
//      .foreach{
//        //@ TODO: DO WE NEED TO CHANGE THE COMPUTATION OF R? SHOULDN'T IT BE A FOLD OVER ALL ELEMENTS, INSTEAD OF A FOREACH
//        case i =>
//          val partialTrade = trade.tradeSequence(i)
//          val td = 1.0 + Math.log(trade.tradeSequence.last.time.toDouble - partialTrade.time.toDouble)
//          val R = initR * Math.pow(gamma,td) + i * r
//
//          val actionProb = partialTrade.actionProbabilities
//          val valuePred = partialTrade.valuePrediction.head
//
//          val actionProbError = actionProb.map(Math.log).map(_ * (R - valuePred))
//          val valueFunError = Math.pow(R - valuePred, 2.0)
//
//          // Do a backward pass through the network
//          val currentGradient: Gradient = network
//            .backpropGradient(
//              Nd4j.create(actionProbError),
//              Nd4j.create(Array(valueFunError))
//            )
//
//
//          val gradForVar = currentGradient.gradientForVariable()
//          gradForVar
//            .asScala
//            .foreach{
//              case (key, grad) =>
//                if (!initialGradient.contains(key)) {
//                  initialGradient.update(key, grad)
//                } else {
//                  initialGradient.update(key, initialGradient(key).add(grad))
//                }
//            }
//      }
//    initialGradient
//  }

  /**
    * Compute the gradient for a given gradient map
    * @param gradientMap
    * @return
    */
  def toGradient(gradientMap: collection.mutable.Map[String, INDArray]):
  DefaultGradient = {

    val gradient = new DefaultGradient()
    gradientMap.foreach{
      case (name, grad) => gradient.setGradientFor(name, grad)
    }
    gradient
  }


}

case class A3CModel(
                     id: String,
                     network: ComputationGraph,
                     gamma: Double = 0.99
                   ) extends Model {

  /**
    * Predict for a given frame and return the action probabilities
    * @return
    */
  def predict(frame: Array[Double]): Map[String, Array[Double]] = {
    // Convert to a mllib vector
    val indFrame = Nd4j.create(frame)
    val indResults = network.output(indFrame)

    indResults
      .map(indarray => indarray.data().asDouble())
      .zipWithIndex
      .map{
        case (arr, 0) => "probabilities" -> arr
        case (arr, 1) => "valueFun" -> arr
      }
      .toMap
  }


  /**
    * Train the model on a given set of trades
    * @return
    */
  def train(trades: Array[Trade]): A3CModel = {
    this

//    val conf = ConfigFactory.load()
//    trades.foreach{
//      trade =>
//        val partialTrade = trade.tradeSequence.head
//        val profit = Trade.computeProfit(trade)
//        val gradientMap = A3CModel.computeGradientMap(network, trade, gamma, profit)
//        val gradient = A3CModel.toGradient(gradientMap)
//        network.update(gradient)
//    }
//
//
//    A3CModel.save(this, conf, id)
  }
}








