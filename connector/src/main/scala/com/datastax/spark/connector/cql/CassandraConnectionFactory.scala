package com.datastax.spark.connector.cql

import java.nio.file.{Files, Path}
import java.security.KeyStore
import java.time.Duration

import com.datastax.bdp.spark.DseCassandraConnectionFactory
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.config.DefaultDriverOption._
import com.datastax.oss.driver.api.core.config.{DriverConfigLoader, ProgrammaticDriverConfigLoaderBuilder}
import com.datastax.oss.driver.internal.core.connection.ExponentialReconnectionPolicy
import com.datastax.oss.driver.internal.core.ssl.DefaultSslEngineFactory
import com.datastax.spark.connector.rdd.ReadConf
import com.datastax.spark.connector.util.{ConfigParameter, ReflectionUtil}
import org.apache.spark.SparkConf

import scala.collection.JavaConverters._
import scala.util.Try

/** Creates both native and Thrift connections to Cassandra.
  * The connector provides a DefaultConnectionFactory.
  * Other factories can be plugged in by setting `spark.cassandra.connection.factory` option. */
trait CassandraConnectionFactory extends Serializable {

  /** Creates and configures native Cassandra connection */
  def createSession(conf: CassandraConnectorConf): CqlSession

  /** List of allowed custom property names passed in SparkConf */
  def properties: Set[String] = Set.empty

  def getScanner(
                  readConf: ReadConf,
                  connConf: CassandraConnectorConf,
                  columnNames: IndexedSeq[String]): Scanner =
    new DefaultScanner(readConf, connConf, columnNames)

}

/** Performs no authentication. Use with `AllowAllAuthenticator` in Cassandra. */
object DefaultConnectionFactory extends CassandraConnectionFactory {

  def connectorConfigBuilder(conf: CassandraConnectorConf, initBuilder: ProgrammaticDriverConfigLoaderBuilder) = {
    type LoaderBuilder = ProgrammaticDriverConfigLoaderBuilder

    def basicProperties(builder: LoaderBuilder): LoaderBuilder = {
      val cassandraCoreThreadCount = Math.max(1, Runtime.getRuntime.availableProcessors() - 1)
      builder
        .withInt(CONNECTION_POOL_LOCAL_SIZE, conf.localConnectionsPerExecutor.getOrElse(cassandraCoreThreadCount)) // moved from CassandraConnector
        .withInt(CONNECTION_POOL_REMOTE_SIZE, conf.remoteConnectionsPerExecutor.getOrElse(1)) // moved from CassandraConnector
        .withInt(CONNECTION_INIT_QUERY_TIMEOUT, conf.connectTimeoutMillis)
        .withInt(REQUEST_TIMEOUT, conf.readTimeoutMillis)
        .withStringList(CONTACT_POINTS, conf.hosts.map(h => s"${h.getHostAddress}:${conf.port}").toList.asJava)
        .withClass(RETRY_POLICY_CLASS, classOf[MultipleRetryPolicy])
        .withClass(RECONNECTION_POLICY_CLASS, classOf[ExponentialReconnectionPolicy])
        .withDuration(RECONNECTION_BASE_DELAY, Duration.ofMillis(conf.minReconnectionDelayMillis))
        .withDuration(RECONNECTION_MAX_DELAY, Duration.ofMillis(conf.maxReconnectionDelayMillis))
        .withClass(LOAD_BALANCING_POLICY_CLASS, classOf[LocalNodeFirstLoadBalancingPolicy])
        .withInt(NETTY_ADMIN_SHUTDOWN_QUIET_PERIOD, conf.quietPeriodBeforeCloseMillis / 1000)
        .withInt(NETTY_ADMIN_SHUTDOWN_TIMEOUT, conf.timeoutBeforeCloseMillis / 1000)
        .withInt(NETTY_IO_SHUTDOWN_QUIET_PERIOD, conf.quietPeriodBeforeCloseMillis / 1000)
        .withInt(NETTY_IO_SHUTDOWN_TIMEOUT, conf.timeoutBeforeCloseMillis / 1000)
        .withBoolean(NETTY_DAEMON, true)
        .withInt(MultipleRetryPolicy.MaxRetryCount, conf.queryRetryCount)
    }

    // compression option cannot be set to NONE (default)
    def compressionProperties(b: LoaderBuilder): LoaderBuilder =
      Option(conf.compression).filter(_ != "NONE").map(c => b.withString(PROTOCOL_COMPRESSION, c.toLowerCase)).getOrElse(b)

    def localDCProperty(b: LoaderBuilder): LoaderBuilder =
      conf.localDC.map(b.withString(LOAD_BALANCING_LOCAL_DATACENTER, _)).getOrElse(b)

    // add ssl properties if ssl is enabled
    def sslProperties(builder: LoaderBuilder): LoaderBuilder = {
      def clientAuthEnabled(value: Option[String]) =
        if (conf.cassandraSSLConf.clientAuthEnabled) value else None

      if (conf.cassandraSSLConf.enabled) {
        Seq(
          SSL_TRUSTSTORE_PATH -> conf.cassandraSSLConf.trustStorePath,
          SSL_TRUSTSTORE_PASSWORD -> conf.cassandraSSLConf.trustStorePassword,
          SSL_KEYSTORE_PATH -> clientAuthEnabled(conf.cassandraSSLConf.keyStorePath),
          SSL_KEYSTORE_PASSWORD -> clientAuthEnabled(conf.cassandraSSLConf.keyStorePassword))
          .foldLeft(builder) { case (b, (name, value)) =>
            value.map(b.withString(name, _)).getOrElse(b)
          }
          .withClass(SSL_ENGINE_FACTORY_CLASS, classOf[DefaultSslEngineFactory])
          .withStringList(SSL_CIPHER_SUITES, conf.cassandraSSLConf.enabledAlgorithms.toList.asJava)
          .withBoolean(SSL_HOSTNAME_VALIDATION, false) // TODO: this needs to be configurable by users. Set to false for our integration tests
      } else {
        builder
      }
    }

    Seq[LoaderBuilder => LoaderBuilder](basicProperties, compressionProperties, localDCProperty, sslProperties)
      .foldLeft(initBuilder) { case (builder, properties) => properties(builder) }
  }

  /** Creates and configures native Cassandra connection */
  override def createSession(conf: CassandraConnectorConf): CqlSession = {
    val configLoaderBuilder = DriverConfigLoader.programmaticBuilder()
    val configLoader = connectorConfigBuilder(conf, configLoaderBuilder).build()

    val builder = CqlSession.builder()
      .withConfigLoader(configLoader)

    conf.authConf.authProvider.foreach(builder.withAuthProvider)
    builder.withSchemaChangeListener(new MultiplexingSchemaListener())

    builder.build()
  }

  private def getKeyStore(
                           ksType: String,
                           ksPassword: Option[String],
                           ksPath: Option[Path]): Option[KeyStore] = {

    ksPath match {
      case Some(path) =>
        val ksIn = Files.newInputStream(path)
        try {
          val keyStore = KeyStore.getInstance(ksType)
          keyStore.load(ksIn, ksPassword.map(_.toCharArray).orNull)
          Some(keyStore)
        } finally {
          Try(ksIn.close())
        }
      case None => None
    }
  }
}

/** Entry point for obtaining `CassandraConnectionFactory` object from [[org.apache.spark.SparkConf SparkConf]],
  * used when establishing connections to Cassandra. */
object CassandraConnectionFactory {

  val ReferenceSection = CassandraConnectorConf.ReferenceSection
  """Name of a Scala module or class implementing
    |CassandraConnectionFactory providing connections to the Cassandra cluster""".stripMargin

  val FactoryParam = ConfigParameter[CassandraConnectionFactory](
    name = "spark.cassandra.connection.factory",
    section = ReferenceSection,
    default = DseCassandraConnectionFactory,
    description =
      """Name of a Scala module or class implementing
        |CassandraConnectionFactory providing connections to the Cassandra cluster""".stripMargin)

  def fromSparkConf(conf: SparkConf): CassandraConnectionFactory = {
    conf.getOption(FactoryParam.name)
      .map(ReflectionUtil.findGlobalObject[CassandraConnectionFactory])
      .getOrElse(FactoryParam.default)
  }

}