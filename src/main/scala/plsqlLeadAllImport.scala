
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
import scala.reflect.io.Directory
import java.io.File
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
import java.sql.CallableStatement;
import java.sql.Types;

object PLSQLLeadAllImport {
  // declaration and definition of function
  def fileRecordCount(sparkContext: JavaSparkContext, importFilePath: String): Long =
    {
      // Start - CSV File record count
      // Read a file
      val stringJavaRDD = sparkContext.textFile(importFilePath);
      var fCount: Long = stringJavaRDD.count();
      return fCount;
      // end - CSV File record count
    }
  
  def uploadStagingData(df: DataFrame,sqlContext: SQLContext, batchId: Int, options: collection.mutable.Map[String, String]): String =
  {
     println("uploadStagingData")
     try {

      df.registerTempTable("ZCA_IMPORT_DATA_INT")
      //LeadId as PK1,
      val renameInputDF = sqlContext.sql("select LeadNumber as ATTRIBUTE_SMALL_CHAR002,CustomerId as ATTRIBUTE_SMALL_CHAR003,PrimaryContactId as ATTRIBUTE_SMALL_CHAR004,LeadName as ATTRIBUTE_SMALL_CHAR005,DealSize as ATTRIBUTE_SMALL_CHAR006,City as ATTRIBUTE_SMALL_CHAR007,State as ATTRIBUTE_SMALL_CHAR008,Country as ATTRIBUTE_SMALL_CHAR009,BudgetAmount as ATTRIBUTE_SMALL_CHAR010,PartyUsageCode as ATTRIBUTE_SMALL_CHAR011,PartyRole as ATTRIBUTE_SMALL_CHAR012,productGroupId as ATTRIBUTE_SMALL_CHAR013,currCode as ATTRIBUTE_SMALL_CHAR014 from ZCA_IMPORT_DATA_INT");
      val unique_df = renameInputDF.withColumn("FILE_RECORD_NUM", monotonically_increasing_id())
        .withColumn("INTERFACE_ROW_ID", monotonically_increasing_id())
        .withColumn("PK1", monotonically_increasing_id())
        .withColumn("CREATION_DATE", lit("01-JAN-06 12.00.00.000000000 AM"))
        .withColumn("LAST_UPDATE_DATE", lit("01-JAN-06 12.00.00.000000000 AM"))
        .withColumn("CREATED_BY", lit("PLSQL"))
        .withColumn("LAST_UPDATED_BY", lit("PLSQL"))
        .withColumn("BATCH_ID", lit(batchId));
      
      println("Final Lead Schema to import ");
      unique_df.printSchema()
      //println("Final Lead data to import ");
      unique_df.cache();//.show();
      df.unpersist();
      renameInputDF.unpersist();

      //unique_df.coalesce(numOfPartitions.toInt).foreachPartition(partition => {
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
    return "SUCCESS";
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
      
	    val importFormatErrorFilePath = "oci://ImportJobs@" + ociNameSpace + "/FormatError/Format_Error_" + inputFileName;
      // Dir Delete
	    val Formatdirectory = new Directory(new File(importFormatErrorFilePath))
      Formatdirectory.deleteRecursively()
      
      val importInvalidErrorFilePath = "oci://ImportJobs@" + ociNameSpace + "/FunctionalError/Invalid_" + inputFileName;
	    val Functionaldirectory = new Directory(new File(importInvalidErrorFilePath))
      Functionaldirectory.deleteRecursively()
      
      val importSucessFilePath = "oci://ImportJobs@" + ociNameSpace + "/SuccessRecords/Success_" + inputFileName;
	    val Successdirectory = new Directory(new File(importSucessFilePath))
      Successdirectory.deleteRecursively()
      
      val ImportJobDetails = "oci://ImportJobs@" + ociNameSpace + "/ImportJob";
      val inputLeadFilePath = "oci://InputData@" + ociNameSpace + "/" + inputFileName;
      

      // File Delete
/*      for {
        files <- Option(new File(importSucessFilePath).listFiles)
        //file <- files if file.getName.endsWith(".csv")
      } file.delete()
*/
      val startTime = Calendar.getInstance.getTime;
      println("Start Time : " + startTime);
      
      val spark = SparkSession.builder()
		              .appName("PLSQLLeadImport")
		              .getOrCreate();
		              
		  val ssparkContext = spark.sparkContext;
		  val sparkContext = new JavaSparkContext(ssparkContext);
		  
		  println("Number of records in input file = " + fileRecordCount(sparkContext, inputLeadFilePath));

      // Start - Read CSV File
      val sqlContext = new SQLContext(sparkContext);

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
      options += ("dbtable" -> "ZCA_IMPORT_DATA_INT")
      
      val brConnect = ssparkContext.broadcast(origConnectionProperties)
      
      println("fin jdbcUrl : " +jdbcUrl);
      println("fin user : " +user);
      println("fin pwd : " +pwd);
      println("fin tmpWalletPath : " +tmpWalletPath);

      println("Number of records in input file = " + fileRecordCount(sparkContext, inputLeadFilePath));

      //custom schema definition of a file
      val LeadSchema = StructType(Array(
        StructField("LeadNumber", StringType, true),
        StructField("CustomerId", LongType, true),
        StructField("PrimaryContactId", LongType, true),
        StructField("LeadName", StringType, true),
        StructField("DealSize", IntegerType, true),
        StructField("City", StringType, true),
        StructField("State", StringType, true),
        StructField("Country", StringType, true),
        StructField("BudgetAmount", IntegerType, true),
        StructField("PartyUsageCode", StringType, true),
        StructField("PartyRole", StringType, true),
        StructField("productGroupId", LongType, true),
        StructField("currCode", StringType, true),
        StructField("_corrupt_record", StringType, true) // used to find malformed rows
      ))
      
      val schemaValidStartTime = System.currentTimeMillis();
      
      val df = sqlContext.read
        .format("com.databricks.spark.csv")
        .option("header", "true")
        //.option("inferSchema","true") // auto find the schema
        .schema(LeadSchema) // use defined schema
        .load(inputLeadFilePath)

      // print schema
      println("Input File Schema : ");
      df.printSchema();
      df.rdd.cache();
      
      df.registerTempTable("ZCA_IMPORT_DATA_INT")

      // find malformed rows
      val badRows = df.filter("_corrupt_record is not null");
      println("Invalid Format Rows from Input File : ");
      badRows.cache().show();

      val schemaValidEndTime = System.currentTimeMillis();
      println("Time taken to Read File and Validate Schema : " + (schemaValidEndTime - schemaValidStartTime).toFloat / 1000 + " seconds");
      
      badRows.coalesce(1).write.mode(SaveMode.Overwrite).format("com.databricks.spark.csv").save(importFormatErrorFilePath)

      val goodRowsDF = df.filter("_corrupt_record is null");

      // Start - Validate
      val ruleValidationStartTime = System.currentTimeMillis();
      
      // filter null partynumbers
      val invalidDF = goodRowsDF.filter("LeadNumber is NULL OR CustomerId is NULL OR PrimaryContactId is NULL OR LeadName is NULL OR PartyUsageCode is NULL")
      val ruleValidationEndTime = System.currentTimeMillis();
      
      println("Invalid Rows (LeadNumber/CustomerId/primaryContactId/leadName/PartyUsageCode is NULL) from Input File : ");
      invalidDF.cache().show()
      println("Time taken for Rule Validation : " + (ruleValidationEndTime - ruleValidationStartTime).toFloat / 1000 + " seconds");
      
      invalidDF.coalesce(1).write.mode(SaveMode.Overwrite).format("com.databricks.spark.csv").save(importInvalidErrorFilePath)
      println("write importInvalidErrorFilePath");
      df.unpersist();
      invalidDF.unpersist();

      val validRowsDF = df.filter("_corrupt_record is NULL AND LeadNumber is not NULL AND CustomerId is not NULL AND PrimaryContactId is not NULL AND LeadName is not NULL AND PartyUsageCode is not NULL AND productGroupId is not NULL")
            .select("LeadNumber", "CustomerId", "PrimaryContactId","LeadName", "DealSize", "City", "State", "Country","BudgetAmount","PartyUsageCode","PartyRole","productGroupId","currCode")
      validRowsDF.cache()
      println("Cleaning DB");
      // End - Validate
      
      cleanLeadStagingTables(sqlContext,ssparkContext,walletName, srcOciPathStr, user, pwd);
      
      val batchId = scala.util.Random.nextInt(1000000);
      println("Batch Id : " + batchId);
      val status = uploadStagingData(validRowsDF, sqlContext,batchId.toInt, options);
      
      leadBaseTableUpload(batchId.toInt, sqlContext,ssparkContext,walletName, srcOciPathStr, user, pwd,importSucessFilePath,importInvalidErrorFilePath)
      
    } catch {
      case scala.util.control.NonFatal(e) =>
        e.printStackTrace
        println(e.printStackTrace);
        //connection.close()
    }
  }
  
  
  def leadBaseTableUpload(batchId: Int,sqlContext: SQLContext,sc: SparkContext,walletName: String, srcOciPathStr: String,user: String,pwd: String,importSucessFilePath: String,importInvalidErrorFilePath: String): String =
  {
    
    	    // ADW wallet and jdbc connection string, 
	    val jdbcUrl = "jdbc:oracle:thin:@db202102142024_high?TNS_ADMIN=/tmp/"+walletName;
	    val jdbcDriver = "oracle.jdbc.driver.OracleDriver"
	    println("jdbc URL : " + jdbcUrl)
		
	    // Populate Connection properties
	    val connectionProperties = new Properties()
	    connectionProperties.put("user",user)
	    connectionProperties.put("password", pwd)
	    connectionProperties.put("jdbcUrl", jdbcUrl)
	    
        	//broadcast jdbc connection parameters to each partition so that they can instantiate JDBC connection locally
	    val brConnect = sc.broadcast(connectionProperties)
        
        var destWalletPathStr = new String("/tmp/");
	      val configuration = new Configuration();
		
	      // Check to see if the /tmp already has the directory
	      var destWalletPathDir = new File(destWalletPathStr+walletName);
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
			    var fileName = fileSrcPath.toString().split("/").reverse(0)

			    val destPathFile = new Path(destWalletPathDir +"/"+ fileName)
			    val srcPathFile = new Path(srcOciPathStr + fileName)			
			    val srcFsStream = bmcFS.open(fileSrcPath);
			    var fos = new FileOutputStream("/tmp/"+walletName+"/"+fileName);
			    val buf = scala.Array.ofDim[Byte](1024)
	
			    // Copy each file using buffered stream
			    var length=srcFsStream.read(buf);
            		while (length > 0) {
                		fos.write(buf, 0, length);
				          length = srcFsStream.read(buf);
            		}
     	  }
		    
		    //Get the broadcasted connected parameters
		    val brconnectionProperties = brConnect.value
			
		    // Extract values from the broadcasted variables
		    val brjdbcURL = brconnectionProperties.getProperty("jdbcUrl")
		    val bruser = brconnectionProperties.getProperty("user")
		    val brpwd = brconnectionProperties.getProperty("password")
			
		    // Connect DataBase individually for each partition
		    Class.forName(jdbcDriver)

		    // Open the data base connection
		    val dbc: Connection = DriverManager.getConnection(brjdbcURL, bruser, brpwd)

		    //dbc.setAutoCommit(true);
		   
		    val leadplsql: String = " declare " + "\n" +
"                    begin "+"\n" +
"                        update zca_import_data_int set import_status_code = 'I' where batch_id = ? ; "+"\n" +
"                        update zca_import_data_int set import_mode = 'U' where batch_id = ? and import_status_code = 'I' and pk1 is not null and pk1 in (select lead_id from mkl_lm_leads);"+"\n" +
"                        update zca_import_data_int set import_status_code = 'E' where batch_id = ? and import_status_code = 'I' and zca_import_data_int.ATTRIBUTE_SMALL_CHAR013 not in (select PROD_GROUP_ID from QSC_PROD_GROUPS_TL);"+"\n" +
"                        INSERT into ZCA_IMPORT_ERRORS (ERROR_ID,BATCH_ID,FILE_RECORD_NUM,ERROR_MESSAGE) "+"\n" + 
"                        select lpad(9000 + rownum, 11, 0),zca_import_data_int.BATCH_ID,zca_import_data_int.FILE_RECORD_NUM,'Invalid Product (PROD_GROUP_ID)' from zca_import_data_int WHERE batch_id = ?  and zca_import_data_int.import_status_code = 'E';"+"\n"+
"                        update zca_import_data_int set import_status_code = 'L' where batch_id = ? and import_status_code = 'E' ;" + "\n" +
"                        update zca_import_data_int set import_status_code = 'E' where batch_id = ? and import_status_code = 'I' and zca_import_data_int.ATTRIBUTE_SMALL_CHAR003 not in (select party_id from HZ_ORGANIZATION_PROFILES);"+"\n" +
"                        INSERT into ZCA_IMPORT_ERRORS (ERROR_ID,BATCH_ID,FILE_RECORD_NUM,ERROR_MESSAGE) "+"\n" + 
"                        select lpad(9100 + rownum, 11, 0),zca_import_data_int.BATCH_ID,zca_import_data_int.FILE_RECORD_NUM,'Invalid Customer (CUSTOMER_ID)' from zca_import_data_int WHERE batch_id = ?  and zca_import_data_int.import_status_code = 'E';"+"\n"+
"                        update zca_import_data_int set import_status_code = 'L' where batch_id = ? and import_status_code = 'E' ;" + "\n" +
"                        update zca_import_data_int set import_status_code = 'E' where batch_id = ? and import_status_code = 'I' and zca_import_data_int.ATTRIBUTE_SMALL_CHAR004 not in (select party_id from HZ_ORGANIZATION_PROFILES);"+"\n" + 
"                        INSERT into ZCA_IMPORT_ERRORS (ERROR_ID,BATCH_ID,FILE_RECORD_NUM,ERROR_MESSAGE) "+"\n" + 
"                        select lpad(9200 + rownum, 11, 0),zca_import_data_int.BATCH_ID,zca_import_data_int.FILE_RECORD_NUM,'Invalid Contact (PRIMARY_CONTACT_ID)' from zca_import_data_int WHERE batch_id = ?  and zca_import_data_int.import_status_code = 'E';"+"\n"+
"                        update zca_import_data_int set import_status_code = 'E' where batch_id = ? and import_status_code = 'L' ;" + "\n" +
"                        merge into mkl_lm_leads"+"\n" +
"                        using zca_import_data_int"+"\n" +
"                        on (mkl_lm_leads.lead_id = zca_import_data_int.pk1 and batch_id = ?)"+"\n" +
"                        when matched then update"+"\n" +
"                        set CUSTOMER_ID = zca_import_data_int.ATTRIBUTE_SMALL_CHAR003,PRIMARY_CONTACT_ID = zca_import_data_int.ATTRIBUTE_SMALL_CHAR004,LEAD_NAME = zca_import_data_int.ATTRIBUTE_SMALL_CHAR005,"+"\n" +
"                        DEAL_SIZE = zca_import_data_int.ATTRIBUTE_SMALL_CHAR006,CITY = zca_import_data_int.ATTRIBUTE_SMALL_CHAR007,STATE = zca_import_data_int.ATTRIBUTE_SMALL_CHAR008,COUNTRY = zca_import_data_int.ATTRIBUTE_SMALL_CHAR009,BUDGET_AMOUNT = zca_import_data_int.ATTRIBUTE_SMALL_CHAR010,"+"\n" +
"                        LAST_UPDATE_DATE = zca_import_data_int.LAST_UPDATE_DATE,LAST_UPDATED_BY = zca_import_data_int.LAST_UPDATED_BY"+" WHERE zca_import_data_int.import_status_code = 'I' and zca_import_data_int.import_mode = 'U'  \n" +
"                        WHEN NOT MATCHED THEN "+"\n" +
"                        INSERT (LEAD_ID,LEAD_NUMBER,CUSTOMER_ID,PRIMARY_CONTACT_ID,LEAD_NAME,DEAL_SIZE,CITY,STATE,COUNTRY,BUDGET_AMOUNT,CREATION_DATE,LAST_UPDATE_DATE,LAST_UPDATED_BY,CREATED_BY) VALUES"+"\n" +
"                        (zca_import_data_int.PK1,zca_import_data_int.ATTRIBUTE_SMALL_CHAR002,zca_import_data_int.ATTRIBUTE_SMALL_CHAR003,zca_import_data_int.ATTRIBUTE_SMALL_CHAR004,zca_import_data_int.ATTRIBUTE_SMALL_CHAR005"+"\n" +
"                        ,zca_import_data_int.ATTRIBUTE_SMALL_CHAR006,zca_import_data_int.ATTRIBUTE_SMALL_CHAR007,zca_import_data_int.ATTRIBUTE_SMALL_CHAR008,zca_import_data_int.ATTRIBUTE_SMALL_CHAR009,zca_import_data_int.ATTRIBUTE_SMALL_CHAR010,zca_import_data_int.CREATION_DATE"+"\n" +
"                        ,zca_import_data_int.LAST_UPDATE_DATE,zca_import_data_int.LAST_UPDATED_BY,zca_import_data_int.CREATED_BY)  WHERE zca_import_data_int.import_status_code = 'I';"+"\n" +
"                        INSERT into MKL_LEAD_TC_MEMBERS"+"\n" +
"                        (MKL_LEAD_TC_MEMBER_ID,LEAD_ID,PARTY_USAGE_CODE,PARTY_ID,PARTY_ROLE,CREATION_DATE,LAST_UPDATE_DATE,LAST_UPDATED_BY,CREATED_BY) "+"\n" +
"                        SELECT lpad(1000 + rownum, 11, 0),zca_import_data_int.PK1,zca_import_data_int.ATTRIBUTE_SMALL_CHAR011,zca_import_data_int.ATTRIBUTE_SMALL_CHAR004,zca_import_data_int.ATTRIBUTE_SMALL_CHAR012,zca_import_data_int.CREATION_DATE,zca_import_data_int.LAST_UPDATE_DATE,zca_import_data_int.LAST_UPDATED_BY,zca_import_data_int.CREATED_BY from zca_import_data_int WHERE batch_id = ? and zca_import_data_int.import_status_code = 'I';"+"\n" +
"                        INSERT into MKL_LEAD_ITEMS_ASSOC"+"\n" +
"                        (PROD_ASSOC_ID,LEAD_ID,PRODUCT_GROUP_ID,CONV_CURR_CODE,CREATION_DATE,LAST_UPDATE_DATE,LAST_UPDATED_BY,CREATED_BY) "+"\n" +
"                        SELECT lpad(1000 + rownum, 11, 0),zca_import_data_int.PK1,zca_import_data_int.ATTRIBUTE_SMALL_CHAR013,zca_import_data_int.ATTRIBUTE_SMALL_CHAR014,zca_import_data_int.CREATION_DATE,zca_import_data_int.LAST_UPDATE_DATE,zca_import_data_int.LAST_UPDATED_BY,zca_import_data_int.CREATED_BY from zca_import_data_int WHERE batch_id = ? and zca_import_data_int.import_status_code = 'I';"+"\n" +
"                        update zca_import_data_int set import_status_code = 'S' where batch_id = ? and import_status_code = 'I'; "+"\n" +
"                        commit;"+"\n" +
"                        ? := 'success';"+"\n" +
"                    end;"
		    
        var cs: CallableStatement = dbc.prepareCall(leadplsql);
		    cs.setString(1, batchId.toString);
		    cs.setString(2, batchId.toString);
		    cs.setString(3, batchId.toString);
		    cs.setString(4, batchId.toString);
		    cs.setString(5, batchId.toString);
		    cs.setString(6, batchId.toString);
		    cs.setString(7, batchId.toString);
		    cs.setString(8, batchId.toString);
		    cs.setString(9, batchId.toString);
        cs.setString(10, batchId.toString);
        cs.setString(11, batchId.toString);        
        cs.setString(12, batchId.toString);
        cs.setString(13, batchId.toString);
        cs.setString(14, batchId.toString);        
        cs.setString(15, batchId.toString);          
        cs.registerOutParameter(16, Types.VARCHAR);
        //cs.registerOutParameter(3, OracleTypes.CURSOR);

        cs.execute();
		    
		    println("result : " + cs.getObject(10));
		    cs.close();
			//dbc.commit // Commit the records
			// Close the Database connection for each partition

       println("close ");
    
			 val rowList = new scala.collection.mutable.MutableList[Row]
       var cRow: Row = null
       
       cRow = RowFactory.create("INTERFACE_ROW_ID","BATCH_ID","FILE_RECORD_NUM","PK1","ATTRIBUTE_SMALL_CHAR002","IMPORT_STATUS_CODE","IMPORT_MODE");
       rowList += (cRow)
       
       val statement = dbc.createStatement()
        val importResultSet = statement.executeQuery("select INTERFACE_ROW_ID,BATCH_ID,FILE_RECORD_NUM,PK1,ATTRIBUTE_SMALL_CHAR002,IMPORT_STATUS_CODE,IMPORT_MODE from ZCA_IMPORT_DATA_INT order by FILE_RECORD_NUM asc");
        //Looping resultset
        var incr = 0;
        while (importResultSet.next()) {
          //    adding one columns into a "Row" object
          cRow = RowFactory.create(if(importResultSet.getObject(1) !=null) importResultSet.getObject(1).toString else importResultSet.getObject(1), 
              if(importResultSet.getObject(2) !=null) importResultSet.getObject(2).toString else importResultSet.getObject(2),
              if(importResultSet.getObject(3) !=null) importResultSet.getObject(3).toString else importResultSet.getObject(3),
              if(importResultSet.getObject(4) !=null) importResultSet.getObject(4).toString else importResultSet.getObject(4),
              if(importResultSet.getObject(5) !=null) importResultSet.getObject(5).toString else importResultSet.getObject(5),
              if(importResultSet.getObject(6) !=null) importResultSet.getObject(6).toString else importResultSet.getObject(6),
              if(importResultSet.getObject(7) !=null) importResultSet.getObject(7).toString else importResultSet.getObject(7))
          //    adding each rows into "List" object.
          incr += 1;
          println("Lead INTERFACE_ROW_ID = " + importResultSet.getObject(1).toString + " :: " + incr)
          rowList += (cRow)
        }

        val impStageSchema = StructType(Array(
        StructField("INTERFACE_ROW_ID",StringType,true),
        StructField("BATCH_ID",StringType,true),
        StructField("FILE_RECORD_NUM",StringType,true),
        StructField("PK1",StringType,true),
        StructField("ATTRIBUTE_SMALL_CHAR002",StringType,true),
        StructField("IMPORT_STATUS_CODE",StringType,true),
        StructField("IMPORT_MODE",StringType,true)))
        //creates a dataframe
        val impSuccessDF = sqlContext.createDataFrame(sc.parallelize(rowList ,1), impStageSchema)
        impSuccessDF.show()
        impSuccessDF.write.format("csv").save(importSucessFilePath)
        //return leadNumDF;
        statement.close();


        // store Errors
        val errorRowList = new scala.collection.mutable.MutableList[Row]
        var cErrorRow: Row = null

        val errorStatement = dbc.createStatement()
        val importErrorResultSet = statement.executeQuery("select ERROR_ID,BATCH_ID,FILE_RECORD_NUM,ERROR_MESSAGE from ZCA_IMPORT_ERRORS order by FILE_RECORD_NUM asc");
        //Looping resultset
        var errIncr = 0;
        while (importErrorResultSet.next()) {
          //    adding one columns into a "Row" object
          cErrorRow = RowFactory.create(if(importErrorResultSet.getObject(1) !=null) importErrorResultSet.getObject(1).toString else importErrorResultSet.getObject(1), 
              if(importErrorResultSet.getObject(2) !=null) importErrorResultSet.getObject(2).toString else importErrorResultSet.getObject(2),
              if(importErrorResultSet.getObject(3) !=null) importErrorResultSet.getObject(3).toString else importErrorResultSet.getObject(3),
              if(importErrorResultSet.getObject(4) !=null) importErrorResultSet.getObject(4).toString else importErrorResultSet.getObject(4))
          //    adding each rows into "List" object.
          errIncr += 1;
          println("Error ERROR_ID = " + importErrorResultSet.getObject(1).toString + " :: " + errIncr)
          errorRowList += (cErrorRow)
        }

        val impErrorStageSchema = StructType(Array(
        StructField("ERROR_ID",StringType,true),
        StructField("BATCH_ID",StringType,true),
        StructField("FILE_RECORD_NUM",StringType,true),
        StructField("ERROR_MESSAGE",StringType,true)))
        //creates a dataframe
        val impErrorFuncDF = sqlContext.createDataFrame(sc.parallelize(errorRowList ,1), impErrorStageSchema)
        impErrorFuncDF.show()
        impErrorFuncDF.write.format("csv").save(importInvalidErrorFilePath)
        //return leadNumDF;
        errorStatement.close();


        dbc.close 
        
    return "SUCCESS";
  }
  
  
  def cleanLeadStagingTables(sqlContext: SQLContext,sc: SparkContext,walletName: String, srcOciPathStr: String,user: String,pwd: String): String =
  {
    
    	    // ADW wallet and jdbc connection string, 
	    val jdbcUrl = "jdbc:oracle:thin:@db202102142024_high?TNS_ADMIN=/tmp/"+walletName;
	    val jdbcDriver = "oracle.jdbc.driver.OracleDriver"
	    println("jdbc URL : " + jdbcUrl)
		
	    // Populate Connection properties
	    val connectionProperties = new Properties()
	    connectionProperties.put("user",user)
	    connectionProperties.put("password", pwd)
	    connectionProperties.put("jdbcUrl", jdbcUrl)
	    
        	//broadcast jdbc connection parameters to each partition so that they can instantiate JDBC connection locally
	    val brConnect = sc.broadcast(connectionProperties)
        
        var destWalletPathStr = new String("/tmp/");
	      val configuration = new Configuration();
		
	      // Check to see if the /tmp already has the directory
	      var destWalletPathDir = new File(destWalletPathStr+walletName);
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
			    var fileName = fileSrcPath.toString().split("/").reverse(0)

			    val destPathFile = new Path(destWalletPathDir +"/"+ fileName)
			    val srcPathFile = new Path(srcOciPathStr + fileName)			
			    val srcFsStream = bmcFS.open(fileSrcPath);
			    var fos = new FileOutputStream("/tmp/"+walletName+"/"+fileName);
			    val buf = scala.Array.ofDim[Byte](1024)
	
			    // Copy each file using buffered stream
			    var length=srcFsStream.read(buf);
            		while (length > 0) {
                		fos.write(buf, 0, length);
				          length = srcFsStream.read(buf);
            		}
     	  }
		    
		    //Get the broadcasted connected parameters
		    val brconnectionProperties = brConnect.value
			
		    // Extract values from the broadcasted variables
		    val brjdbcURL = brconnectionProperties.getProperty("jdbcUrl")
		    val bruser = brconnectionProperties.getProperty("user")
		    val brpwd = brconnectionProperties.getProperty("password")
			
		    // Connect DataBase individually for each partition
		    Class.forName(jdbcDriver)

		    // Open the data base connection
		    val dbc: Connection = DriverManager.getConnection(brjdbcURL, bruser, brpwd)

		    val leadplsql: String = " declare " + "\n" +
"                    begin "+"\n" +
"                        delete from ZCA_IMPORT_DATA_INT; "+"\n" +
"                        delete from MKL_LM_LEADS where country is not null;"+"\n" +
"                        delete from MKL_LEAD_ITEMS_ASSOC; "+"\n" +
"                        delete from MKL_LEAD_TC_MEMBERS; "+"\n" +
"                        commit;"+"\n" +
"                    end;"
		    
        var cs: CallableStatement = dbc.prepareCall(leadplsql);
        cs.execute();
		    cs.close();
			// Close the Database connection for each partition
       println("close ");
       dbc.close 
        
    return "SUCCESS";
  }
  
}