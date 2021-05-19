
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

object LeadImport {
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

  def standardizeLeadItemsDF(validLeadItemsDF: DataFrame, sqlContext: SQLContext): DataFrame =
  {
      // Add default columns
      validLeadItemsDF.registerTempTable("LeadItemsInput")

      val renameInputDF = sqlContext.sql("select LEAD_ID,productGroupId as PRODUCT_GROUP_ID,currCode as CONV_CURR_CODE from LeadItemsInput");
//      println(renameInputDF.columns);
//      renameInputDF.printSchema();
//      renameInputDF.show();

      val unique_df = renameInputDF.withColumn("PROD_ASSOC_ID", monotonicallyIncreasingId)
        .withColumn("CREATION_DATE", lit("01-JAN-06 12.00.00.000000000 AM"))
        .withColumn("LAST_UPDATE_DATE", lit("01-JAN-06 12.00.00.000000000 AM"))
        .withColumn("CREATED_BY", lit("GS1"))
        .withColumn("LAST_UPDATED_BY", lit("GS1"));

      println("Final Lead Items Schema to import ");
      unique_df.printSchema()
      //println("Final Lead Items data to import ");
      unique_df.cache();//.show();

      validLeadItemsDF.unpersist();
      renameInputDF.unpersist();
      return unique_df;
  }

  def writeLeadItemsToDB(unique_df: DataFrame, sc: SparkContext,connectionProperties: Properties,walletName: String, srcOciPathStr: String, jdbcDriver: String, jdbcUrl: String, numOfPartitions: Int, db_batchCommitSize: Int): String =
  {
      //broadcast jdbc connection parameters to each partition so that they can instantiate JDBC connection locally
	    val brConnect = sc.broadcast(connectionProperties)
        
      unique_df.coalesce(numOfPartitions).foreachPartition(partition => {
        
		    println("dataframe : numOfPartitions : " + numOfPartitions)
		    println("db : batchCommitSize : " + db_batchCommitSize)
		    
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
		    val connectionProperties = brConnect.value
			
		    // Extract values from the broadcasted variables
		    val jdbcURL = connectionProperties.getProperty("jdbcUrl")
		    val user = connectionProperties.getProperty("user")
		    val pwd = connectionProperties.getProperty("password")
			
		    // Connect DataBase individually for each partition
		    Class.forName(jdbcDriver)

		    // Open the data base connection
		    val dbc: Connection = DriverManager.getConnection(jdbcUrl, user, pwd)

		    dbc.setAutoCommit(true);
		    var st: PreparedStatement = null
		    
		    // Browse through each row in the partition
		    partition.grouped(db_batchCommitSize).foreach(batch => {
			    batch.foreach { row => 
				    {
				    // get the column from dataframe row
				    val PROD_ASSOC_ID = row.getLong(row.fieldIndex("PROD_ASSOC_ID")).toString
             val LEAD_ID = row.getLong(row.fieldIndex("LEAD_ID")).toString
				    val PRODUCT_GROUP_ID = row.getString(row.fieldIndex("PRODUCT_GROUP_ID"))
				    val CONV_CURR_CODE = row.getLong(row.fieldIndex("CONV_CURR_CODE")).toString
				    val CREATION_DATE = "10-JAN-21 12.00.00.000000000 AM"
				    val LAST_UPDATE_DATE = "10-JAN-21 12.00.00.000000000 AM"
				    val LAST_UPDATED_BY = "GS1"
				    val CREATED_BY = "GS1"

				    // Check the dataframe record exists
				    val whereCol: List[String] = List("PROD_ASSOC_ID")
				    val sqlString = "SELECT * from MKL_LEAD_ITEMS_ASSOC where PROD_ASSOC_ID =?"
				    var pstmt: PreparedStatement = dbc.prepareStatement(sqlString)
				    pstmt.setString(1, LEAD_ID)
				    
				    println("PROD_ASSOC_ID : " + PROD_ASSOC_ID)
				    
				    val rs = pstmt.executeQuery()
				    var count: Int = 0
				    while (rs.next()) { count = 1 }
					
				    // Check whether it is UPDATE or INSERT operation
				    var dmlOprtn = "NULL"
				    var sqlUpsertString = "NULL"
				    if (count > 0) {
					    dmlOprtn = "UPDATE"
					    sqlUpsertString = "UPDATE MKL_LEAD_ITEMS_ASSOC SET LEAD_ID=?,PRODUCT_GROUP_ID=?,CONV_CURR_CODE=?,LAST_UPDATE_DATE=?,LAST_UPDATED_BY=? WHERE PROD_ASSOC_ID=?"
					    st = dbc.prepareStatement(sqlUpsertString)
				
					    st.setString(1,LEAD_ID)
					    st.setString(2,PRODUCT_GROUP_ID)
					    st.setString(3,CONV_CURR_CODE)
					    st.setString(4,LAST_UPDATE_DATE)
					    st.setString(5,LAST_UPDATED_BY)
					    st.setString(6,PROD_ASSOC_ID)	
					    
					    println("update : " + sqlUpsertString)
				    }
				    else {
					    dmlOprtn = "INSERT"
					    sqlUpsertString = "INSERT INTO MKL_LEAD_ITEMS_ASSOC(PROD_ASSOC_ID,LEAD_ID,PRODUCT_GROUP_ID,CONV_CURR_CODE,CREATION_DATE,LAST_UPDATE_DATE,LAST_UPDATED_BY,CREATED_BY) VALUES (?,?,?,?,?,?,?,?)"
					    st = dbc.prepareStatement(sqlUpsertString)

					    st.setString(1,PROD_ASSOC_ID)	
					    st.setString(2,LEAD_ID)
					    st.setString(3,PRODUCT_GROUP_ID)
					    st.setString(4,CONV_CURR_CODE)
					    st.setString(5,CREATION_DATE)
					    st.setString(6,LAST_UPDATE_DATE)
					    st.setString(7,LAST_UPDATED_BY)
					    st.setString(8,CREATED_BY)

					    println("insert : " + sqlUpsertString)
				    }
				    st.addBatch() //add the dataframe records to the batch update
				    pstmt.close()
				    println(" PROD_ASSOC_ID =========> " + PROD_ASSOC_ID + "   =======   " + dmlOprtn)
				  }
				    
				  println("execute Batch ")
				  st.executeBatch() //Execute the batched records
				  st.close()
				}
			  //dbc.commit
			})
			//dbc.commit // Commit the records
			dbc.close // Close the Database connection for each partition
		})
      
    println("close ");
    
    unique_df.unpersist();
    
    return "SUCCESS";

  }
  
  def standardizeLeadDF(validPartyDF: DataFrame, sqlContext: SQLContext): DataFrame =
  {
      // Add default columns
      validPartyDF.registerTempTable("LeadInput")

      val renameInputDF = sqlContext.sql("select LEAD_ID,LeadNumber as LEAD_NUMBER, customerId as CUSTOMER_ID,primaryContactId as PRIMARY_CONTACT_ID,leadName as LEAD_NAME,dealSize as DEAL_SIZE,city,state,BudgetAmount as BUDGET_AMOUNT from LeadInput");
//      println(renameInputDF.columns);
//      renameInputDF.printSchema();
//      renameInputDF.show();

      val unique_df = renameInputDF//.withColumn("LEAD_ID", monotonicallyIncreasingId)
        .withColumn("CREATION_DATE", lit("01-JAN-06 12.00.00.000000000 AM"))
        .withColumn("LAST_UPDATE_DATE", lit("01-JAN-06 12.00.00.000000000 AM"))
        .withColumn("CREATED_BY", lit("GS1"))
        .withColumn("LAST_UPDATED_BY", lit("GS1"));

      println("Final Lead Schema to import ");
      unique_df.printSchema()
      //println("Final Lead data to import ");
      unique_df.cache();//.show();

      validPartyDF.unpersist();
      renameInputDF.unpersist();
      return unique_df;
}
  
  def standardizeLeadResourceDF(validPartyDF: DataFrame, sqlContext: SQLContext): DataFrame =
  {
      // Add default columns
      validPartyDF.registerTempTable("LeadResourceInput")

      val renameInputDF = sqlContext.sql("select LEAD_ID,PartyUsageCode as PARTY_USAGE_CODE,primaryContactId as PARTY_ID,PartyRole as PARTY_ROLE from LeadResourceInput");
      println(renameInputDF.columns);
//      renameInputDF.printSchema();
//      renameInputDF.show();

      val unique_df = renameInputDF.withColumn("MKL_LEAD_TC_MEMBER_ID", monotonicallyIncreasingId)
        .withColumn("CREATION_DATE", lit("01-JAN-06 12.00.00.000000000 AM"))
        .withColumn("LAST_UPDATE_DATE", lit("01-JAN-06 12.00.00.000000000 AM"))
        .withColumn("CREATED_BY", lit("GS1"))
        .withColumn("LAST_UPDATED_BY", lit("GS1"));

      println("Final Lead Resource Schema to import ");
      unique_df.printSchema()
      //println("Final Lead Resource data to import ");
      unique_df.cache();//.show();

      validPartyDF.unpersist();
      renameInputDF.unpersist();
      return unique_df;
}

  def writeLeadResourceToDB(unique_df: DataFrame, sc: SparkContext,connectionProperties: Properties,walletName: String, srcOciPathStr: String, jdbcDriver: String, jdbcUrl: String, numOfPartitions: Int, db_batchCommitSize: Int): String =
  {
      //broadcast jdbc connection parameters to each partition so that they can instantiate JDBC connection locally
	    val brConnect = sc.broadcast(connectionProperties)
        
      unique_df.coalesce(numOfPartitions).foreachPartition(partition => {
        
		    println("dataframe : numOfPartitions : " + numOfPartitions)
		    println("db : batchCommitSize : " + db_batchCommitSize)
		    
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
		    val connectionProperties = brConnect.value
			
		    // Extract values from the broadcasted variables
		    val jdbcURL = connectionProperties.getProperty("jdbcUrl")
		    val user = connectionProperties.getProperty("user")
		    val pwd = connectionProperties.getProperty("password")
			
		    // Connect DataBase individually for each partition
		    Class.forName(jdbcDriver)

		    // Open the data base connection
		    val dbc: Connection = DriverManager.getConnection(jdbcUrl, user, pwd)

		    dbc.setAutoCommit(true);
		    var st: PreparedStatement = null
		    
		    // Browse through each row in the partition
		    partition.grouped(db_batchCommitSize).foreach(batch => {
			    batch.foreach { row => 
				    {
				    // get the column from dataframe row
				    val MKL_LEAD_TC_MEMBER_ID = row.getLong(row.fieldIndex("MKL_LEAD_TC_MEMBER_ID")).toString
             val LEAD_ID = row.getLong(row.fieldIndex("LEAD_ID")).toString
				    val PARTY_USAGE_CODE = row.getString(row.fieldIndex("PARTY_USAGE_CODE"))
				    val PARTY_ID = row.getLong(row.fieldIndex("PARTY_ID")).toString
				    val PARTY_ROLE = row.getLong(row.fieldIndex("PARTY_ROLE")).toString
				    val CREATION_DATE = "10-JAN-21 12.00.00.000000000 AM"
				    val LAST_UPDATE_DATE = "10-JAN-21 12.00.00.000000000 AM"
				    val LAST_UPDATED_BY = "GS1"
				    val CREATED_BY = "GS1"

				    // Check the dataframe record exists
				    val whereCol: List[String] = List("MKL_LEAD_TC_MEMBER_ID")
				    val sqlString = "SELECT * from MKL_LEAD_TC_MEMBERS where MKL_LEAD_TC_MEMBER_ID =?"
				    var pstmt: PreparedStatement = dbc.prepareStatement(sqlString)
				    pstmt.setString(1, LEAD_ID)
				    
				    println("MKL_LEAD_TC_MEMBER_ID : " + MKL_LEAD_TC_MEMBER_ID)
				    
				    val rs = pstmt.executeQuery()
				    var count: Int = 0
				    while (rs.next()) { count = 1 }
					
				    // Check whether it is UPDATE or INSERT operation
				    var dmlOprtn = "NULL"
				    var sqlUpsertString = "NULL"
				    if (count > 0) {
					    dmlOprtn = "UPDATE"
					    sqlUpsertString = "UPDATE MKL_LEAD_TC_MEMBERS SET LEAD_ID=?,PARTY_USAGE_CODE=?,PARTY_ID=?,PARTY_ROLE=?,LAST_UPDATE_DATE=?,LAST_UPDATED_BY=? WHERE MKL_LEAD_TC_MEMBER_ID=?"
					    st = dbc.prepareStatement(sqlUpsertString)
				
					    st.setString(1,LEAD_ID)
					    st.setString(2,PARTY_USAGE_CODE)
					    st.setString(3,PARTY_ID)
					    st.setString(4,PARTY_ROLE)
					    st.setString(5,LAST_UPDATE_DATE)
					    st.setString(6,LAST_UPDATED_BY)
					    st.setString(7,MKL_LEAD_TC_MEMBER_ID)	
					    
					    println("update : " + sqlUpsertString)
				    }
				    else {
					    dmlOprtn = "INSERT"
					    sqlUpsertString = "INSERT INTO MKL_LEAD_TC_MEMBERS(MKL_LEAD_TC_MEMBER_ID,LEAD_ID,PARTY_USAGE_CODE,PARTY_ID,PARTY_ROLE,CREATION_DATE,LAST_UPDATE_DATE,LAST_UPDATED_BY,CREATED_BY) VALUES (?,?,?,?,?,?,?,?,?)"
					    st = dbc.prepareStatement(sqlUpsertString)

					    st.setString(1,MKL_LEAD_TC_MEMBER_ID)	
					    st.setString(2,LEAD_ID)
					    st.setString(3,PARTY_USAGE_CODE)
					    st.setString(4,PARTY_ID)
					    st.setString(5,PARTY_ROLE)
					    st.setString(6,CREATION_DATE)
					    st.setString(7,LAST_UPDATE_DATE)
					    st.setString(8,LAST_UPDATED_BY)
					    st.setString(9,CREATED_BY)

					    println("insert : " + sqlUpsertString)
				    }
				    st.addBatch() //add the dataframe records to the batch update
				    pstmt.close()
				    println(" MKL_LEAD_TC_MEMBER_ID =========> " + MKL_LEAD_TC_MEMBER_ID + "   =======   " + dmlOprtn)
				  }
				    
				  println("execute Batch ")
				  st.executeBatch() //Execute the batched records
				  st.close()
				}
			  //dbc.commit
			})
			//dbc.commit // Commit the records
			dbc.close // Close the Database connection for each partition
		})
      
    println("close ");
    
    unique_df.unpersist();
    
    return "SUCCESS";
      
  }

  def writeLeadToDB(unique_df: DataFrame, sc: SparkContext,connectionProperties: Properties,walletName: String, srcOciPathStr: String, jdbcDriver: String, jdbcUrl: String, numOfPartitions: Int, db_batchCommitSize: Int): String =
  {
        	//broadcast jdbc connection parameters to each partition so that they can instantiate JDBC connection locally
	    val brConnect = sc.broadcast(connectionProperties)
        
      unique_df.coalesce(numOfPartitions).foreachPartition(partition => {
        
		    println("dataframe : numOfPartitions : " + numOfPartitions)
		    println("db : batchCommitSize : " + db_batchCommitSize)
		    
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
		    val connectionProperties = brConnect.value
			
		    // Extract values from the broadcasted variables
		    val jdbcURL = connectionProperties.getProperty("jdbcUrl")
		    val user = connectionProperties.getProperty("user")
		    val pwd = connectionProperties.getProperty("password")
			
		    // Connect DataBase individually for each partition
		    Class.forName(jdbcDriver)

		    // Open the data base connection
		    val dbc: Connection = DriverManager.getConnection(jdbcUrl, user, pwd)

		    dbc.setAutoCommit(true);
		    var st: PreparedStatement = null
		    
		    // Browse through each row in the partition
		    partition.grouped(db_batchCommitSize).foreach(batch => {
			    batch.foreach { row => 
				    {
				    // get the column from dataframe row
            val LEAD_ID = row.getLong(row.fieldIndex("LEAD_ID")).toString
				    val LEAD_NUMBER = row.getString(row.fieldIndex("LEAD_NUMBER"))
				    val CUSTOMER_ID = row.getLong(row.fieldIndex("CUSTOMER_ID")).toString
				    val PRIMARY_CONTACT_ID = row.getLong(row.fieldIndex("PRIMARY_CONTACT_ID")).toString
				    val LEAD_NAME = row.getString(row.fieldIndex("LEAD_NAME"))
				    val DEAL_SIZE = row.getInt(row.fieldIndex("DEAL_SIZE")).toString
				    val CITY = row.getString(row.fieldIndex("city"))
				    val STATE = row.getString(row.fieldIndex("state"))
				    val BUDGET_AMOUNT = row.getInt(row.fieldIndex("BUDGET_AMOUNT")).toString
				    val CREATION_DATE = "01-JAN-06 12.00.00.000000000 AM"
				    val LAST_UPDATE_DATE = "01-JAN-06 12.00.00.000000000 AM"
				    val LAST_UPDATED_BY = "GS1"
				    val CREATED_BY = "GS1"

				    // Check the dataframe record exists
				    val whereCol: List[String] = List("LEAD_ID")
				    val sqlString = "SELECT * from MKL_LM_LEADS where LEAD_ID =?"
				    var pstmt: PreparedStatement = dbc.prepareStatement(sqlString)
				    pstmt.setString(1, LEAD_ID)
				    
				    println("leadid : " + LEAD_ID)
				    
				    val rs = pstmt.executeQuery()
				    var count: Int = 0
				    while (rs.next()) { count = 1 }
					
				    // Check whether it is UPDATE or INSERT operation
				    var dmlOprtn = "NULL"
				    var sqlUpsertString = "NULL"
				    if (count > 0) {
					    dmlOprtn = "UPDATE"
					    sqlUpsertString = "UPDATE MKL_LM_LEADS SET CUSTOMER_ID=?, PRIMARY_CONTACT_ID=?,LEAD_NAME=?,CITY=?,STATE=?,BUDGET_AMOUNT=?,LAST_UPDATE_DATE=?,LAST_UPDATED_BY=? WHERE LEAD_ID=?"
					    st = dbc.prepareStatement(sqlUpsertString)
				
					    st.setString(1,CUSTOMER_ID)
					    st.setString(2,PRIMARY_CONTACT_ID)
					    st.setString(3,LEAD_NAME)
					    st.setString(4,CITY)
					    st.setString(5,STATE)
					    st.setString(6,BUDGET_AMOUNT)
					    st.setString(7,LAST_UPDATE_DATE)
					    st.setString(8,LAST_UPDATED_BY)
					    st.setString(9,LEAD_ID)	
					    
					    println("update : " + sqlUpsertString)
				    }
				    else {
					    dmlOprtn = "INSERT"
					    sqlUpsertString = "INSERT INTO MKL_LM_LEADS(LEAD_ID,LEAD_NUMBER,CUSTOMER_ID,PRIMARY_CONTACT_ID,LEAD_NAME,CITY,STATE,BUDGET_AMOUNT,CREATION_DATE,LAST_UPDATE_DATE,LAST_UPDATED_BY,CREATED_BY) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
					    st = dbc.prepareStatement(sqlUpsertString)

					    st.setString(1,LEAD_ID)	
					    st.setString(2,LEAD_NUMBER)
					    st.setString(3,CUSTOMER_ID)
					    st.setString(4,PRIMARY_CONTACT_ID)
					    st.setString(5,LEAD_NAME)
					    st.setString(6,CITY)
					    st.setString(7,STATE)
					    st.setString(8,BUDGET_AMOUNT)
					    st.setString(9,CREATION_DATE)
					    st.setString(10,LAST_UPDATE_DATE)
					    st.setString(11,LAST_UPDATED_BY)
					    st.setString(12,CREATED_BY)

					    println("insert : " + sqlUpsertString)
				    }
				    st.addBatch() //add the dataframe records to the batch update
				    pstmt.close()
				    println(" LEAD_ID =========> " + LEAD_ID + "   =======   " + dmlOprtn)
				  }
				    
				  println("execute Batch ")
				  st.executeBatch() //Execute the batched records
				  st.close()
				}
			  //dbc.commit
			})
			//dbc.commit // Commit the records
			dbc.close // Close the Database connection for each partition
		})
      
    println("close ");
    
    unique_df.unpersist();
    
    return "SUCCESS";
  }


  def readProductInfo(productFilePath: String,sparkContext: JavaSparkContext): DataFrame =
  {

      // Start - Read CSV File
      val sqlContext = new SQLContext(sparkContext);

        val productFileDF = sqlContext.read
          .format("com.databricks.spark.csv")
          .option("header", "true")
          .option("inferSchema", "true") // auto find the schema
          //.schema(LeadSchema) // use defined schema
          .load(productFilePath)
          //.select("lead_number")
//        leadFileDF.show();
        return productFileDF;
        
    }
    
  
  def readLeadInfo(leadFilePath: String,sparkContext: JavaSparkContext): DataFrame =
  {
      // Start - Read CSV File
      val sqlContext = new SQLContext(sparkContext);

        val leadFileDF = sqlContext.read
          .format("com.databricks.spark.csv")
          .option("header", "true")
          .option("inferSchema", "true") // auto find the schema
          //.schema(LeadSchema) // use defined schema
          .load(leadFilePath)
          //.select("lead_number")
//        leadFileDF.show();
        return leadFileDF;
    }
  
  
  def readPartyInfo(accountFilePath: String, sparkContext: JavaSparkContext): DataFrame =
    {
      // Start - Read CSV File
      val sqlContext = new SQLContext(sparkContext);

        val partyIDDF = sqlContext.read
          .format("com.databricks.spark.csv")
          .option("header", "true")
          .option("inferSchema", "true") // auto find the schema
          //.schema(LeadSchema) // use defined schema
          .load(accountFilePath)
//          .select("party_id")
//        partyIDDF.show();
        return partyIDDF;
    }

  
  def main(args: Array[String]) {
    println("Hello World")
    try {
      
      val ociNameSpace = args(0);
      // Object Storage - buckets
      val importFilePath = "oci://InputData@" + ociNameSpace + "/" + args(1);
      
      	val user = args(2);
	    val pwd = args(3)

      // Location of the wallet on the object store, it will be given as input for the application, unzip wallet contents here
      //val srcOciPathStr = new String("oci://dataflowadwwallet@bigdatadatasciencelarge/<walletFilename>");
      val srcOciPathStr = args(4)
      
      val numOfPartitions = args(5)
      
      val batchCommitSize = args(6)
	
      // Get the wallet directory name
      val walletName = srcOciPathStr.split("/").reverse(0)

	    // ADW wallet and jdbc connection string, 
	    val jdbcUrl = "jdbc:oracle:thin:@db202102142024_high?TNS_ADMIN=/tmp/" + walletName
	    val jdbcDriver = "oracle.jdbc.driver.OracleDriver"
		
	    // Populate Connection properties
	    val connectionProperties = new Properties()
	    connectionProperties.put("user",user)
	    connectionProperties.put("password", pwd)
	    connectionProperties.put("jdbcUrl", jdbcUrl)	
      
      val importFormatErrorFilePath = "oci://ImportJobs@" + ociNameSpace + "/FormatError/Format_Error_" + args(1);
      val importInvalidErrorFilePath = "oci://ImportJobs@" + ociNameSpace + "/FunctionalError/Invalid_" + args(1);
      val importSucessFilePath = "oci://ImportJobs@" + ociNameSpace + "/SuccessRecords/Success_";
      val ImportJobDetails = "oci://ImportJobs@" + ociNameSpace + "/ImportJob";
      val accountFilePath = "oci://Accounts@" + ociNameSpace + "/hz_parties_1.6M.csv";
      val productFilePath = "oci://ProductGroup@" + ociNameSpace + "/ProductGroups1k.csv";
      val leadFilePath = "oci://Leads@" + ociNameSpace + "/mkl_lm_leads_200k.csv";
      
      val startTime = Calendar.getInstance.getTime;
      println("Start Time : " + startTime);

      /*
      val sparkConf = new SparkConf()
        .setAppName("Example Spark App")
        .setMaster("local[*]") // Delete this line when submitting to a cluster
      val sparkContext = new JavaSparkContext(sparkConf);

      println("Number of records in input file = " + fileRecordCount(sparkContext, importFilePath));

      val spark = SparkSession
        .builder()
        .master("local[*]")
        .appName("Spark SQL basic example")
        .config("spark.some.config.option", "some-value")
        .getOrCreate()

      // Start - Read CSV File
      val sqlContext = new SQLContext(sparkContext);
            val df = sqlContext.read
        .format("com.databricks.spark.csv")
        .option("header", "true")
        //.option("inferSchema","true") // auto find the schema
        .schema(LeadSchema) // use defined schema
        .load(importFilePath)
      //.select($"rownum", $"name") // used to choose a subset of columns
       */
      
      val spark = SparkSession.builder()
		              .appName("LeadImport")
		              .getOrCreate();
		              
		  val ssparkContext = spark.sparkContext;
		  val sparkContext = new JavaSparkContext(ssparkContext);
		  
		  println("Number of records in input file = " + fileRecordCount(sparkContext, importFilePath));

      // Start - Read CSV File
      val sqlContext = new SQLContext(sparkContext);

      //custom schema definition of a file
      val LeadSchema = StructType(Array(
        StructField("leadNumber", StringType, true),
        StructField("customerId", LongType, true),
        StructField("primaryContactId", LongType, true),
        StructField("leadName", StringType, true),
        StructField("dealSize", IntegerType, true),
        StructField("city", StringType, true),
        StructField("state", StringType, true),
        StructField("Country", StringType, true),
        StructField("PartyUsageCode", StringType, true),
        StructField("PartyRole", StringType, true),
        StructField("productGroupId", LongType, true),
        StructField("currCode", StringType, true),
        StructField("BudgetAmount", IntegerType, true),
        StructField("_corrupt_record", StringType, true) // used to find malformed rows
      ))

      
      val schemaValidStartTime = System.currentTimeMillis();
      
       val df = sqlContext.read
        .format("com.databricks.spark.csv")
        .option("header", "true")
        //.option("inferSchema","true") // auto find the schema
        .schema(LeadSchema) // use defined schema
        .load(importFilePath)

      // print schema
      println("Input File Schema : ");
      df.printSchema();
      df.rdd.cache();
      // print file
      //df.rdd.foreach(println)

      // register data frame as db template
      df.registerTempTable("LeadInFile")

      // find malformed rows
      val badRows = df.filter("_corrupt_record is not null");
      println("Invalid Format Rows from Input File : ");
      badRows.cache().show();

      val schemaValidEndTime = System.currentTimeMillis();
      println("Time taken to Read File and Validate Schema : " + (schemaValidEndTime - schemaValidStartTime).toFloat / 1000 + " seconds");
      
      badRows.coalesce(1).write.mode(SaveMode.Overwrite).format("com.databricks.spark.csv").save(importFormatErrorFilePath)

      val goodRowsDF = df.filter("_corrupt_record is null");
      //read data as sql
      //sqlContext.sql("SELECT * from Account where rownum >= 100").collect.foreach(println)
      //sqlContext.sql("SELECT * from Account where rownum >= 100 GROUP BY name").collect.foreach(println)
      //sqlContext.sql("SELECT * from Account").collect.foreach(println)

// Start - Validate
      
      val ruleValidationStartTime = System.currentTimeMillis();
      
      // filter null partynumbers
      val invalidDF = goodRowsDF.filter("LeadNumber is NULL OR CustomerId is NULL OR primaryContactId is NULL OR leadName is NULL OR PartyUsageCode is NULL")
      val ruleValidationEndTime = System.currentTimeMillis();
      
      println("Invalid Rows (LeadNumber/CustomerId/primaryContactId/leadName/PartyUsageCode is NULL) from Input File : ");
      invalidDF.cache().show()
      println("Time taken for Rule Validation : " + (ruleValidationEndTime - ruleValidationStartTime).toFloat / 1000 + " seconds");
      
      invalidDF.coalesce(1).write.mode(SaveMode.Overwrite).format("com.databricks.spark.csv").save(importInvalidErrorFilePath)
      println("write importInvalidErrorFilePath");
      df.unpersist();
      invalidDF.unpersist();

      val validRowsDF = df.filter("_corrupt_record is NULL AND LeadNumber is not NULL AND CustomerId is not NULL AND primaryContactId is not NULL AND leadName is not NULL AND PartyUsageCode is not NULL AND productGroupId is not NULL")
            .select("leadNumber", "customerId", "primaryContactId",
        "leadName", "dealSize", "city", "state", "Country","PartyUsageCode","PartyRole","productGroupId","currCode","BudgetAmount")
      validRowsDF.cache()
      //validRowsDF.show()
      println("filter _corrupt_record");
      // End - Validate

      // Get parties
      val partyIDDF = readPartyInfo(accountFilePath,sparkContext)
      println("readPartyInfo");
      val ProductIdDF = readProductInfo(productFilePath,sparkContext)
      println("readProductInfo");
      partyIDDF.cache();

      // validate parties
      println("Checking validity of Customer id with 1.6M parties");
      val partyIdValidationStartTime = System.currentTimeMillis();
      val validateCustomerIdDF = validRowsDF.join(right = partyIDDF, validRowsDF("customerId") <=> partyIDDF("party_id"), joinType = "left_semi")
      println("valid leads with valid customerid")
      //validateCustomerIdDF.show()
      val inValidCustomerIdDF = validRowsDF.join(right = partyIDDF, validRowsDF("customerId") <=> partyIDDF("party_id"), joinType = "left_anti")
      println("invalid leads with invalid customerid : " + inValidCustomerIdDF.count())
      inValidCustomerIdDF.show()
      val partyIdValidationEndTime = System.currentTimeMillis();
      println("Time taken to Validate CustomerId : " + (partyIdValidationEndTime - partyIdValidationStartTime).toFloat / 1000 + " seconds");
      
      // Validate primaryContactId
      println("Checking validity of primaryContactId with 1.6M parties");
      val primaryContactIdValidationStartTime = System.currentTimeMillis();
      val validatePrimaryContactIdDF = validateCustomerIdDF.join(right = partyIDDF, validateCustomerIdDF("primaryContactId") <=> partyIDDF("party_id"), joinType = "left_semi")
      //println("valid leads with valid primaryContactId")
      //validatePrimaryContactIdDF.show()
      val inValidatePrimaryContactIdDF = validateCustomerIdDF.join(right = partyIDDF, validateCustomerIdDF("primaryContactId") <=> partyIDDF("party_id"), joinType = "left_anti")
      println("invalid leads with invalid primaryContactId : " + inValidatePrimaryContactIdDF.count())
      inValidatePrimaryContactIdDF.show()
      val primaryContactIdValidationEndTime = System.currentTimeMillis();
      println("Time taken to Validate primaryContactId : " + (primaryContactIdValidationEndTime - primaryContactIdValidationStartTime).toFloat / 1000 + " seconds");
      
      // Validate Product
      println("Checking for valid Lead Product using Lead Number with 1.3K products");
      val LeadProductValidationStartTime = System.currentTimeMillis();
      val validProductIdDF = validatePrimaryContactIdDF.join(right = ProductIdDF, validatePrimaryContactIdDF("productGroupId") <=> ProductIdDF("prod_group_id"), joinType = "left_semi")
      //println("valid leads with valid productGroupId")
      //validPartyDF.show();
      val inValidProductIdDF = validatePrimaryContactIdDF.join(right = ProductIdDF, validatePrimaryContactIdDF("productGroupId") <=> ProductIdDF("prod_group_id"), joinType = "left_anti")
      println("invalid leads with invalid productGroupId : " + inValidProductIdDF.count())
      inValidProductIdDF.show()
      val LeadProductValidationEndTime = System.currentTimeMillis();
      println("Time taken to Validate productGroupId : " + (LeadProductValidationEndTime - LeadProductValidationStartTime).toFloat / 1000 + " seconds");
      
      println(" Writing invalid data to File : " + importInvalidErrorFilePath);
      inValidCustomerIdDF.coalesce(1).write.mode(SaveMode.Append).format("com.databricks.spark.csv").save(importInvalidErrorFilePath);
      inValidatePrimaryContactIdDF.coalesce(1).write.mode(SaveMode.Append).format("com.databricks.spark.csv").save(importInvalidErrorFilePath);
      inValidProductIdDF.coalesce(1).write.mode(SaveMode.Append).format("com.databricks.spark.csv").save(importInvalidErrorFilePath);

      //release memory
      inValidCustomerIdDF.unpersist();
      partyIDDF.unpersist();
      validRowsDF.unpersist();
      
      // Validate LeadNumber
      val leadNumberDF = readLeadInfo(leadFilePath,sparkContext)

      // validate leads
      println("Checking mode of Lead using Lead NUmber with 182K leads : ");
      val LeadNumberValidationStartTime = System.currentTimeMillis();
      val updateLeadDF = validProductIdDF.join(right = leadNumberDF, validProductIdDF("leadNumber") <=> leadNumberDF("lead_number"), joinType = "left_semi");
      println("Leads to Update : " + updateLeadDF.count())
      //updateLeadDF.show();

      val insertLeadDF = validProductIdDF.join(right = leadNumberDF, validRowsDF("leadNumber") <=> leadNumberDF("lead_number"), joinType = "left_anti")
      .withColumn("LEAD_ID", monotonicallyIncreasingId)
      .withColumn("DealPerformance", validRowsDF("BudgetAmount") - validRowsDF("DealSize"))
      println("Leads to Insert :" + insertLeadDF.count())
      //insertLeadDF.show();
      val LeadNumberValidationEndTime = System.currentTimeMillis();
      println("Time taken to find Lead mode : " + (LeadNumberValidationEndTime - LeadNumberValidationStartTime).toFloat / 1000 + " seconds");
      
      val LeadNumberDeDupStartTime = System.currentTimeMillis();
      val dedupLeadDF = insertLeadDF.dropDuplicates("leadNumber");
      val dupLeadDF = insertLeadDF.except(dedupLeadDF);
      val dupCount = dupLeadDF.count();
      println("De Duplication based on LeadNumber : Duplicate Leads : " + dupCount);
      if(dupCount > 0 )
        dupLeadDF.show();
      
      insertLeadDF.unpersist();
      dupLeadDF.unpersist();
      
      val LeadNumberDeDupEndTime = System.currentTimeMillis();
      
      println("Number of lead outPerformed : " + insertLeadDF.filter("DealPerformance > 0").count());
      println("Number of lead that met expectations on budget : " + insertLeadDF.filter("DealPerformance = 0").count());
      println("Number of lead underPerformed : " + insertLeadDF.filter("DealPerformance < 0").count());
                   
      if(insertLeadDF.count() > 0 ){ 
        println("Inserting Leads into MKL_LM_LEADS:")
        val dataWriteStartTime = System.currentTimeMillis();
        val Lead_df = standardizeLeadDF(dedupLeadDF, sqlContext)
        
        //   import leads
        //   Start - Write in to DB
        val leadStatus = writeLeadToDB(Lead_df,ssparkContext,connectionProperties,walletName,srcOciPathStr,jdbcDriver,jdbcUrl,numOfPartitions.toInt, batchCommitSize.toInt);
        Lead_df.coalesce(1).write.mode(SaveMode.Overwrite).format("com.databricks.spark.csv").save(importSucessFilePath + "Lead.csv")
        // destWalletPathStr+walletName,srcOciPathStr,jdbcDriver,jdbcUrl

        val dataWriteLeadEndTime = System.currentTimeMillis();
        println("Imported Leads Successfully to DB in : " + (dataWriteLeadEndTime - dataWriteStartTime).toFloat / 1000 + " seconds")
        
        println("Inserting into Lead Resources(mkl_lead_tc_members) :")
        val LeadResource_df = standardizeLeadResourceDF(dedupLeadDF, sqlContext)
        val leadResourceStatus = writeLeadResourceToDB(LeadResource_df,ssparkContext,connectionProperties,walletName,srcOciPathStr,jdbcDriver,jdbcUrl,numOfPartitions.toInt, batchCommitSize.toInt);
        LeadResource_df.coalesce(1).write.mode(SaveMode.Overwrite).format("com.databricks.spark.csv").save(importSucessFilePath + "LeadResource.csv")
        
        val dataWriteLeadResourceEndTime = System.currentTimeMillis();
        println("Imported Lead Resources Successfully to DB in : " + (dataWriteLeadResourceEndTime - dataWriteLeadEndTime).toFloat / 1000 + " seconds")
        
        println("Inserting into Lead Items(MKL_LEAD_ITEMS_ASSOC) :")
        val LeadItems_df = standardizeLeadItemsDF(dedupLeadDF, sqlContext)
        val leadItemsStatus = writeLeadItemsToDB(LeadItems_df,ssparkContext,connectionProperties,walletName,srcOciPathStr,jdbcDriver,jdbcUrl,numOfPartitions.toInt, batchCommitSize.toInt);
        LeadItems_df.coalesce(1).write.mode(SaveMode.Overwrite).format("com.databricks.spark.csv").save(importSucessFilePath+"LeadItems.csv")
        
        val dataWriteLeadItemsEndTime = System.currentTimeMillis();
        println("Imported Lead Items Successfully to DB in : " + (dataWriteLeadItemsEndTime - dataWriteLeadResourceEndTime).toFloat / 1000 + " seconds")
        
        val dataWriteEndTime = System.currentTimeMillis();
        println("Time taken to write data to Leads/LeadResources/LeadItemAssoc : " + (dataWriteEndTime - dataWriteStartTime).toFloat / 1000 + " seconds");
        
        //println("Total Deals made : " + Lead_df.select(col("DealSize")).rdd.map(_(0).asInstanceOf[Int]).reduce(_+_))
        //println("Total Budget Allocated : " + Lead_df.select(col("BudgetAmount")).rdd.map(_(0).asInstanceOf[Int]).reduce(_+_))
        
        //val dfWithDiff = insertLeadDF.withColumn("DealPerformance", insertLeadDF("BudgetAmount") - insertLeadDF("DealSize"))

        println("Number of lead outPerformed : " + insertLeadDF.filter("DealPerformance > 0").count());
        println("Number of lead that met expectations on budget : " + insertLeadDF.filter("DealPerformance = 0").count());
        println("Number of lead underPerformed : " + insertLeadDF.filter("DealPerformance < 0").count());
                
        println("End Time : " + Calendar.getInstance.getTime);
      } else{
        println ("No data to import");
      }
      
    } catch {
      case scala.util.control.NonFatal(e) =>
        e.printStackTrace
        println(e.printStackTrace);
        //connection.close()
    }
  }
}