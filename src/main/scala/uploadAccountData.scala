

import org.apache.spark.sql.Dataset;
//import scalax.chart._
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.Row;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.SparkContext;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.{ StructType, IntegerType, StringType, LongType, StructField, ArrayType};
import org.apache.spark.sql.DataFrameWriter;
import org.apache.spark.sql.SaveMode;
import java.util.Properties;
import java.sql.DriverManager
import java.sql.Connection
import com.databricks.spark.csv
import org.apache.spark.sql.RowFactory
import org.apache.spark.sql.functions._
import java.io.File
import java.util.Calendar

//import java.sql._
import java.util.Properties
import org.apache.spark.sql.execution.datasources.jdbc.JDBCOptions
import oracle.jdbc.pool.OracleDataSource;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.driver.OracleDriver;
import java.io.FileOutputStream;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import com.oracle.bmc.hdfs.BmcFilesystem;
import java.net.URI;
import org.apache.hadoop.fs.FileStatus;
import java.io.BufferedOutputStream;
import java.sql.PreparedStatement;

object uploadAccountData {
    def fileRecordCount(sparkContext: JavaSparkContext, importFilePath: String): Long =
    {
      // Start - CSV File record count
      // Read a file
      val stringJavaRDD = sparkContext.textFile(importFilePath);
      var fCount: Long = stringJavaRDD.count();
      return fCount;
      // end - CSV File record count
    }

  
  def main(args: Array[String]) {
    println("Hello World")
    try {
      
      val ociNameSpace = args(0);
      // Object Storage - buckets      
      	val user = args(1);
	    val pwd = args(2)

      // Location of the wallet on the object store, it will be given as input for the application, unzip wallet contents here
      //val srcOciPathStr = new String("oci://dataflowadwwallet@bigdatadatasciencelarge/<walletFilename>");
      val srcOciPathStr = args(3)
      val numOfPartitions = args(4)
      
      val inputFileName = args(5)
      
      val counter = args(6)
      
      // Get the wallet directory name
      val walletName = srcOciPathStr.split("/").reverse(0)

	    // ADW wallet and jdbc connection string, 
	    val jdbcUrl = "jdbc:oracle:thin:@db202102142024_high?TNS_ADMIN=/opt/spark/work-dir/";
	    //+ walletName
	    val jdbcDriver = "oracle.jdbc.driver.OracleDriver"
	    println("jdbc URL : " + jdbcUrl)
		
	    // Populate Connection properties
	    val origConnectionProperties = new Properties()
	    origConnectionProperties.put("user",user)
	    origConnectionProperties.put("password", pwd)
	    origConnectionProperties.put("jdbcUrl", jdbcUrl)	
      
      val accountFilePath = "oci://Accounts@" + ociNameSpace + "/" + inputFileName;
      val productFilePath = "oci://ProductGroup@" + ociNameSpace + "/ProductGroups1k.csv";
      val leadFilePath = "oci://Leads@" + ociNameSpace + "/mkl_lm_leads_200k.csv";
      
      val startTime = Calendar.getInstance.getTime;
      println("Start Time : " + startTime);
      
      val spark = SparkSession.builder()
		              .appName("uploadData")
		              .getOrCreate();
		              
		  val ssparkContext = spark.sparkContext;
		  val sparkContext = new JavaSparkContext(ssparkContext);
		  
		  println("Number of records in input file = " + fileRecordCount(sparkContext, accountFilePath));

      // Start - Read CSV File
      val sqlContext = new SQLContext(sparkContext);
      
       val df = sqlContext.read
        .format("com.databricks.spark.csv")
        .option("header", "true")
        .load(accountFilePath)

      // print schema
      println("Input File Schema : ");
      df.printSchema();
      df.rdd.cache();

      //val leadStatus = writeLeadToDB(Lead_df,ssparkContext,connectionProperties,walletName,srcOciPathStr,jdbcDriver,jdbcUrl,numOfPartitions.toInt, batchCommitSize.toInt);
      
      df.registerTempTable("HZ_ORGANIZATION_PROFILES")
      val renameInputDF = sqlContext.sql("select PARTY_NUMBER,SOURCE_SYSTEM,SOURCE_SYSTEM_REFERENCE,PREF_FUNCTIONAL_CURRENCY,ORGANIZATION_NAME,SALES_PROFILE_TYPE,OWNER_PARTY_NUMBER,OWNER_PARTY_ID,ORGANIZATION_TYPE,NAMED_FLAG,ADDRESS_LINE,COUNTRY,STATE,COUNTY,CITY,POSTAL_CODE,GEOGRAPHY_ID,INDUSTRY_CODE,ORG_TYPE_CATEGORY,ORG_TYPE from HZ_ORGANIZATION_PROFILES");
      val unique_df = renameInputDF.withColumn("PARTY_ID", monotonically_increasing_id())
        .withColumn("CREATION_DATE", lit("01-JAN-06 12.00.00.000000000 AM"))
        .withColumn("LAST_UPDATE_DATE", lit("01-JAN-06 12.00.00.000000000 AM"))
        .withColumn("CREATED_BY", lit("GS1"))
        .withColumn("LAST_UPDATED_BY", lit("GS1"));

      unique_df.withColumn("PARTY_ID",col("PARTY_ID")+counter)
      
      println("Final Account Schema to import ");
      unique_df.printSchema()
      //println("Final Lead data to import ");
      unique_df.cache();//.show();
      df.unpersist();
      renameInputDF.unpersist();

      val brConnect = ssparkContext.broadcast(origConnectionProperties)
        
      //unique_df.coalesce(numOfPartitions.toInt).foreachPartition(partition => {
        
		  //  println("dataframe : numOfPartitions : " + numOfPartitions)
		    
        var destWalletPathStr = new String("/opt/spark/work-dir/");
	      val configuration = new Configuration();
		
	      // Check to see if the /tmp already has the directory
	      var destWalletPathDir = new File(destWalletPathStr);
         if (!destWalletPathDir.exists()) {
          	if (destWalletPathDir.mkdir()) {
            	println("Directory is created!");
            } else {
            	println("Failed to create directory!");
            }
	        }

		    // The following code is to copy wallet contents into the /tmp directory before we instantiate the jdbc connection 
		    val config = new Configuration();

		    config.set("fs.oci.client.auth.delegationTokenPath", "/var/run/secrets/oracle.dfcs/token");
		    config.set("fs.oci.client.custom.client","oracle.dfcs.hdfs.DelegationObjectStorageClient");
		    config.set("fs.oci.client.custom.authenticator","com.oracle.bmc.hdfs.auth.InstancePrincipalsCustomAuthenticator");
    		  config.set("fs.oci.client.hostname","https://objectstorage.us-phoenix-1.oraclecloud.com");
    		  
		    // We need to read it as file unlike spark data to be copied into /tmp destination
		    val bmcFS = new BmcFilesystem()
		    // val ociBucketUri = new String("oci://dataflowadwwallet@bigdatadatasciencelarge");
		    //bmcFS.initialize(new URI(ociBucketUri),config)
		    bmcFS.initialize(new URI(srcOciPathStr),config)
		    var lstStatus = bmcFS.listStatus(new Path(srcOciPathStr));
		
		    // iterate through the directory to get all the contents in the wallet directory
		    for ( x <-  lstStatus ) {
			    var fileSrcPath = x.getPath()
			    println("source path : " + fileSrcPath.toString())
			    var fileName = fileSrcPath.toString().split("/").reverse(0)
          println("source file : " + fileName)
			    val destPathFile = new Path(destWalletPathDir +"/"+ fileName)
			    println("dest path : " + destPathFile.toString())
			    val srcPathFile = new Path(srcOciPathStr +"/"+ fileName)			
			    println("srcPathFile : " + srcPathFile.toString())
			    val srcFsStream = bmcFS.open(fileSrcPath);
			    var fos = new FileOutputStream("/opt/spark/work-dir/"+fileName);
			    
			    val buf = scala.Array.ofDim[Byte](1024)
	        
			    // Copy each file using buffered stream
			    var length=srcFsStream.read(buf);
            		while (length > 0) {
                		fos.write(buf, 0, length);
				          length = srcFsStream.read(buf);
            		}
          ssparkContext.addFile("/opt/spark/work-dir/"+fileName);
     	  }
		    
		    //Get the broadcasted connected parameters
		    //val connectionProperties = brConnect.value
        val tmpWalletPath = "/opt/spark/work-dir/";
        var options = collection.mutable.Map[String, String]()
        options += ("driver" -> "oracle.jdbc.driver.OracleDriver")
        options += ("url" -> jdbcUrl)
        options += (OracleConnection.CONNECTION_PROPERTY_USER_NAME -> user)
        options += (OracleConnection.CONNECTION_PROPERTY_PASSWORD -> pwd)
        options += (OracleConnection.CONNECTION_PROPERTY_TNS_ADMIN -> tmpWalletPath)
        options += ("dbtable" -> "HZ_ORGANIZATION_PROFILES")
        
        println("fin jdbcUrl : " +jdbcUrl);
        println("fin user : " +user);
        println("fin pwd : " +pwd);
        println("fin tmpWalletPath : " +tmpWalletPath);
        
        unique_df.write
        .format("jdbc")
        .options(options)
        .mode(SaveMode.Append)
        .save()
        
      unique_df.unpersist();
      //})
      
    } catch {
      case scala.util.control.NonFatal(e) =>
        e.printStackTrace
        println(e.printStackTrace);
        //connection.close()
    }
  }
  
}