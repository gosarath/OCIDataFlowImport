package samplehw

import org.apache.spark.sql.Dataset;
//import scalax.chart._
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.Row;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.SparkContext;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.{ StructType, IntegerType, StringType, LongType, StructField };
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

object leadimp {
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

  def writeLeadItemsToDB(unique_df: DataFrame): String =
  {
      val connectionProperties = new Properties()
      connectionProperties.put("driver", "oracle.jdbc.OracleDriver")
      connectionProperties.put("numPartitions", "10") // parallel threads

      unique_df.write
        .mode(SaveMode.Append)
        .jdbc("jdbc:oracle:thin:fusion/welcome1@slc14hiy.us.oracle.com:1521/r13csmcf_b", "fusion.MKL_LEAD_ITEMS_ASSOC", connectionProperties)

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

  def writeLeadResourceToDB(unique_df: DataFrame): String =
  {
      val connectionProperties = new Properties()
      connectionProperties.put("driver", "oracle.jdbc.OracleDriver")
      connectionProperties.put("numPartitions", "10") // parallel threads

      unique_df.write
        .mode(SaveMode.Append)
        .jdbc("jdbc:oracle:thin:fusion/welcome1@slc14hiy.us.oracle.com:1521/r13csmcf_b", "fusion.MKL_LEAD_TC_MEMBERS", connectionProperties)

      unique_df.unpersist();
      return "SUCCESS";
  }

  def writeLeadToDB(unique_df: DataFrame): String =
  {
      val connectionProperties = new Properties()
      connectionProperties.put("driver", "oracle.jdbc.OracleDriver")
      connectionProperties.put("numPartitions", "10") // parallel threads

      unique_df.write
        .mode(SaveMode.Append)
        .jdbc("jdbc:oracle:thin:fusion/welcome1@slc14hiy.us.oracle.com:1521/r13csmcf_b", "fusion.MKL_LM_LEADS", connectionProperties)

      unique_df.unpersist();
      return "SUCCESS";
  }


  def readProductInfo(productFilePath: String,sparkContext: JavaSparkContext): DataFrame =
  {
  
    // Get Leads
      val fileExists = (new File(productFilePath)).exists()

      // Start - Read CSV File
      val sqlContext = new SQLContext(sparkContext);

      if (fileExists) {

        val productFileDF = sqlContext.read
          .format("com.databricks.spark.csv")
          .option("header", "true")
          .option("inferSchema", "true") // auto find the schema
          //.schema(LeadSchema) // use defined schema
          .load(productFilePath)
          //.select("lead_number")
//        leadFileDF.show();
        return productFileDF;
      } else {

        val spark = SparkSession
          .builder()
          .master("local[*]")
          .appName("Spark SQL basic example")
          .config("spark.some.config.option", "some-value")
          .getOrCreate()
        val url = "jdbc:oracle:thin:fusion/welcome1@slc14hiy.us.oracle.com:1521/r13csmcf_b"
        val rowList = new scala.collection.mutable.MutableList[Row]
        var cRow: Row = null

        // make the connection
        val driver = "oracle.jdbc.OracleDriver"
        Class.forName(driver)
        var connection: Connection = null
        connection = DriverManager.getConnection(url)
        // create the statement, and run the select query
        val statement = connection.createStatement()
        val productResultSet = statement.executeQuery("select TO_CHAR(prod_group_id) from QSC_PROD_GROUPS_TL where LANGUAGE like 'US'")

        //Looping resultset
        var incr = 0;
        while (productResultSet.next()) {
          //    adding one columns into a "Row" object
          cRow = RowFactory.create(productResultSet.getObject(1)) //, resultSet.getObject(2))
          //    adding each rows into "List" object.
          incr += 1;
          println("Product Group Id = " + productResultSet.getObject(1) + " :: " + incr)
          rowList += (cRow)
        }

        val ProdGroupSchema = StructType(Array(
        StructField("prod_group_id",StringType,true)))
        //creates a dataframe
        val ProdGroupDF = sqlContext.createDataFrame(sparkContext.parallelize(rowList ,1), ProdGroupSchema)
        ProdGroupDF.show()
        ProdGroupDF.write.format("csv").save(productFilePath)
        return ProdGroupDF;
      }
        
    }
    
  
  def readLeadInfo(leadFilePath: String,sparkContext: JavaSparkContext): DataFrame =
  {
  
    // Get Leads
      val fileExists = (new File(leadFilePath)).exists()

      // Start - Read CSV File
      val sqlContext = new SQLContext(sparkContext);

      if (fileExists) {

        val leadFileDF = sqlContext.read
          .format("com.databricks.spark.csv")
          .option("header", "true")
          .option("inferSchema", "true") // auto find the schema
          //.schema(LeadSchema) // use defined schema
          .load(leadFilePath)
          //.select("lead_number")
//        leadFileDF.show();
        return leadFileDF;
      } else {

        val spark = SparkSession
          .builder()
          .master("local[*]")
          .appName("Spark SQL basic example")
          .config("spark.some.config.option", "some-value")
          .getOrCreate()
        val url = "jdbc:oracle:thin:fusion/welcome1@slc14hiy.us.oracle.com:1521/r13csmcf_b"
        val rowList = new scala.collection.mutable.MutableList[Row]
        var cRow: Row = null

        // make the connection
        val driver = "oracle.jdbc.OracleDriver"
        Class.forName(driver)
        var connection: Connection = null
        connection = DriverManager.getConnection(url)
        // create the statement, and run the select query
        val statement = connection.createStatement()
        val leadResultSet = statement.executeQuery("select LEAD_NUMBER from fusion.MKL_LM_LEADS where creation_date > sysdate - 100")

        //Looping resultset
        var incr = 0;
        while (leadResultSet.next()) {
          //    adding one columns into a "Row" object
          cRow = RowFactory.create(leadResultSet.getObject(1)) //, resultSet.getObject(2))
          //    adding each rows into "List" object.
          incr += 1;
          println("Lead Number = " + leadResultSet.getObject(1) + " :: " + incr)
          rowList += (cRow)
        }

        val LeadNumSchema = StructType(Array(
        StructField("lead_number",StringType,true)))
        //creates a dataframe
        val leadNumDF = sqlContext.createDataFrame(sparkContext.parallelize(rowList ,1), LeadNumSchema)
        leadNumDF.show()
        leadNumDF.write.format("csv").save(leadFilePath)
        return leadNumDF;
      }
        
    }
  
  
  def readPartyInfo(accountFilePath: String, sparkContext: JavaSparkContext): DataFrame =
    {
      val fileExists = (new File(accountFilePath)).exists()
      // Start - Read CSV File
      val sqlContext = new SQLContext(sparkContext);

      if (fileExists) {

        val partyIDDF = sqlContext.read
          .format("com.databricks.spark.csv")
          .option("header", "true")
          .option("inferSchema", "true") // auto find the schema
          //.schema(LeadSchema) // use defined schema
          .load(accountFilePath)
//          .select("party_id")
//        partyIDDF.show();
        return partyIDDF;
      } else {

        val spark = SparkSession
          .builder()
          .master("local[*]")
          .appName("Spark SQL basic example")
          .config("spark.some.config.option", "some-value")
          .getOrCreate()
  /* Very slow
    			val partyIDDF = spark.read
				  .format("jdbc")
				  .option("url", "jdbc:oracle:thin:fusion/welcome1@slc14hiy.us.oracle.com:1521/r13csmcf_b")
				  .option("dbtable", "fusion.hz_parties")
				  .option("driver", "oracle.jdbc.OracleDriver")
				  .load()
				  .select("party_id")
				  .where("STATUS like 'A' and creation_date > '20-JUL-19 06.09.21.331000000 PM'")
				  //.orderBy("creation_date");

    			partyIDDF.printSchema()
    			partyIDDF.show()
    			println(" Party count : " + partyIDDF.count())
    			partyIDDF.write.format("csv").save(accountFilePath)
  */
        val url = "jdbc:oracle:thin:fusion/welcome1@slc14hiy.us.oracle.com:1521/r13csmcf_b"
        val rowList = new scala.collection.mutable.MutableList[Row]
        var cRow: Row = null

        // make the connection
        val driver = "oracle.jdbc.OracleDriver"
        Class.forName(driver)
        var connection: Connection = null
        connection = DriverManager.getConnection(url)
        // create the statement, and run the select query
        val statement = connection.createStatement()
        val partyResultSet = statement.executeQuery("select TO_CHAR(party_id) from fusion.hz_parties where STATUS like 'A' and creation_date > sysdate - 100")

        //Looping resultset
        var incr = 0;
        while (partyResultSet.next()) {
          //    adding one columns into a "Row" object
          cRow = RowFactory.create(partyResultSet.getObject(1)) //, resultSet.getObject(2))
          //    adding each rows into "List" object.
          incr += 1;
          println("party id = " + partyResultSet.getObject(1) + " :: " + incr)
          rowList += (cRow)
        }

        val PartySchema = StructType(Array(
          StructField("party_id", StringType, true)))
        //creates a dataframe
        val partyIDDF = sqlContext.createDataFrame(sparkContext.parallelize(rowList, 1), PartySchema)
        partyIDDF.show()
        partyIDDF.write.format("csv").save(accountFilePath)
        return partyIDDF;
      }
    }

  
  def main(args: Array[String]) {
    println("Hello World")
    var connection: Connection = null
    val driver = "oracle.jdbc.OracleDriver"
    try {
      
      val startTime = Calendar.getInstance.getTime;
      println("Start Time : " + startTime);

      val folder = "D:\\Spark\\spark-2.4.3-bin-hadoop2.7\\scala ide\\projects\\samplehw\\dataFile\\";
      val inputFileName = "SLead_50k.csv";
      val importFilePath = folder.concat(inputFileName);
      val importFormatErrorFilePath = folder.concat("FormatError"); //.concat(inputFileName);
      val importInvalidErrorFilePath = folder.concat("InvalidError"); //.concat(inputFileName);
      val accountFilePath = "D:\\Spark\\spark-2.4.3-bin-hadoop2.7\\scala ide\\projects\\samplehw\\dataFile\\hz_parties_1.6M.csv"
      val leadFilePath = "D:\\Spark\\spark-2.4.3-bin-hadoop2.7\\scala ide\\projects\\samplehw\\dataFile\\mkl_lm_leads_200k.csv"
      val productFilePath = "D:\\Spark\\spark-2.4.3-bin-hadoop2.7\\scala ide\\projects\\samplehw\\dataFile\\ProductGroups1k.csv"

      val sparkConf = new SparkConf()
        .setAppName("Example Spark App")
        .setMaster("local[*]") // Delete this line when submitting to a cluster
      /*
          .config("spark.executor.memory", "70g")
          .config("spark.driver.memory", "50g")
          .config("spark.memory.offHeap.enabled",true)
          .config("spark.memory.offHeap.size","16g")

          */
      val sparkContext = new JavaSparkContext(sparkConf);

      System.out.println("Number of records in input file = " + fileRecordCount(sparkContext, importFilePath));

      val spark = SparkSession
        .builder()
        .master("local[*]")
        .appName("Spark SQL basic example")
        .config("spark.some.config.option", "some-value")
        .getOrCreate()

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
      //.select($"rownum", $"name") // used to choose a subset of columns

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
      
      badRows.write.mode(SaveMode.Overwrite).format("com.databricks.spark.csv").save(importFormatErrorFilePath)

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
      
      invalidDF.write.mode(SaveMode.Overwrite).format("com.databricks.spark.csv").save(importInvalidErrorFilePath)

      df.unpersist();
      invalidDF.unpersist();

      val validRowsDF = df.filter("_corrupt_record is NULL AND LeadNumber is not NULL AND CustomerId is not NULL AND primaryContactId is not NULL AND leadName is not NULL AND PartyUsageCode is not NULL AND productGroupId is not NULL")
            .select("leadNumber", "customerId", "primaryContactId",
        "leadName", "dealSize", "city", "state", "Country","PartyUsageCode","PartyRole","productGroupId","currCode","BudgetAmount")
      validRowsDF.cache()
      //validRowsDF.show()

      // End - Validate

      // Get parties
      val partyIDDF = readPartyInfo(accountFilePath,sparkContext)
      val ProductIdDF = readProductInfo(productFilePath,sparkContext)
      partyIDDF.cache();

      // validate parties
      println("Checking validity of Customer id with 1.6M parties");
      val partyIdValidationStartTime = System.currentTimeMillis();
      val validateCustomerIdDF = validRowsDF.join(right = partyIDDF, validRowsDF("customerId") <=> partyIDDF("party_id"), joinType = "left_semi")
      //println("valid leads with valid customerid")
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
      inValidCustomerIdDF.write.mode(SaveMode.Append).format("com.databricks.spark.csv").save(importInvalidErrorFilePath);
      inValidatePrimaryContactIdDF.write.mode(SaveMode.Append).format("com.databricks.spark.csv").save(importInvalidErrorFilePath);
      inValidProductIdDF.write.mode(SaveMode.Append).format("com.databricks.spark.csv").save(importInvalidErrorFilePath);

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
      
      //println("Number of lead outPerformed : " + insertLeadDF.filter("DealPerformance > 0").count());
      //println("Number of lead that met expectations on budget : " + insertLeadDF.filter("DealPerformance = 0").count());
      //println("Number of lead underPerformed : " + insertLeadDF.filter("DealPerformance < 0").count());
                   
      if(insertLeadDF.count() > 0 ){ 
        println("Inserting Leads into MKL_LM_LEADS:")
        val dataWriteStartTime = System.currentTimeMillis();
        val Lead_df = standardizeLeadDF(dedupLeadDF, sqlContext)
        
        //   import leads
        //   Start - Write in to DB
        val leadStatus = writeLeadToDB(Lead_df);
        val dataWriteLeadEndTime = System.currentTimeMillis();
        println("Imported Leads Successfully to DB in : " + (dataWriteLeadEndTime - dataWriteStartTime).toFloat / 1000 + " seconds")
        
        println("Inserting into Lead Resources(mkl_lead_tc_members) :")
        val LeadResource_df = standardizeLeadResourceDF(dedupLeadDF, sqlContext)
        val leadResourceStatus = writeLeadResourceToDB(LeadResource_df);
        val dataWriteLeadResourceEndTime = System.currentTimeMillis();
        println("Imported Lead Resources Successfully to DB in : " + (dataWriteLeadResourceEndTime - dataWriteLeadEndTime).toFloat / 1000 + " seconds")
        
        println("Inserting into Lead Items(MKL_LEAD_ITEMS_ASSOC) :")
        val LeadItems_df = standardizeLeadItemsDF(dedupLeadDF, sqlContext)
        val leadItemsStatus = writeLeadItemsToDB(LeadItems_df);
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
      case e =>
        e.printStackTrace
        System.out.println(e.printStackTrace);
        //connection.close()
    }
  }
}