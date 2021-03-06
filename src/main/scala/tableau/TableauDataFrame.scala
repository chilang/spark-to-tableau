package tableau

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.types._
import com.tableausoftware.extract._
import com.tableausoftware.common._
import org.slf4j.Logger
import org.slf4j.LoggerFactory


object TableauDataFrame {
  implicit def applyTableau(df:DataFrame) = new TableauDataFrameImplicity(df)
}

class TableauDataFrameImplicity(df:DataFrame) extends Serializable {

  val logger:Logger = LoggerFactory.getLogger(classOf[TableauDataFrameImplicity])

  def saveToTableau(filename:String) = {
    val colTypes = columnTypes()
    val columnIndexes:Seq[(Int, Type, Int)] = getParquetColumnsIndexes(colTypes, df)
    df.repartition(1).foreachPartition { it =>
      logger.info("Creating tableau table")
      val table = createTableauTable(colTypes, filename)
      logger.info("Tableau table created")
      val tableDef = table.getTableDefinition()
      logger.info("Inserting rows in Tableau table")
      it.map(createTableauRowFromParquetRow(tableDef, columnIndexes, _))
        .foreach(table.insert)
    }
    logger.info("Tableau extractor created '{}'", filename)
  }

  private def columnTypes():Seq[(String, Type)] = {
    df.schema.fields.map{
        f => (f.name, dataFrameTypeToTableauType(f.dataType))
    }
  }

  private def dataFrameTypeToTableauType(dataType:DataType):Type = {
    dataType match  {
      case StringType => Type.CHAR_STRING
      case IntegerType => Type.INTEGER
      case LongType => Type.INTEGER
      case DoubleType => Type.DOUBLE
      case BooleanType => Type.BOOLEAN
      case DateType => Type.DATETIME
    }
  }
  
  private def makeTableDefinition(columnsTypes:Seq[(String, Type)]):TableDefinition = {
    val tableDef:TableDefinition = new TableDefinition();
    tableDef.setDefaultCollation(Collation.PT_BR);
    columnsTypes.foreach((tableDef.addColumn _).tupled)
    tableDef;
  }

  private def getParquetColumnsIndexes(colTypes:Seq[(String, Type)], df: org.apache.spark.sql.DataFrame) = {
    colTypes.zipWithIndex.map{ 
      case((columnName, columnType), i) => (i, columnType, df.schema.fieldIndex(columnName))
    }
  }

  private def createTableauTable(colTypes:Seq[(String, Type)], filename:String): Table = {
    ExtractAPI.initialize()
    val extract:Extract = new Extract(filename)
    val table:Table =  if (!extract.hasTable("Extract")) {
      val tblDef:TableDefinition = makeTableDefinition(colTypes)
      extract.addTable("Extract", tblDef)
    } else {
      extract.openTable("Extract")
    }
    table
  }

  private def createTableauRowFromParquetRow(tableDef:TableDefinition, columnIndexes:Seq[(Int, Type, Int)], parquetRow:org.apache.spark.sql.Row):Row = {
    val row:Row = new Row(tableDef)
    columnIndexes.foreach{ 
      case(i, columnType, columnIndex) => 
        if (parquetRow.get(columnIndex) == null){
          row.setNull(i)
        } else {
          columnType match { 
            case (Type.CHAR_STRING) => row.setCharString(i, parquetRow.getString(columnIndex))
            case (Type.INTEGER) => 
              try{
                row.setInteger(i, parquetRow.getInt(columnIndex))
              } catch{
                case e:java.lang.ClassCastException => row.setInteger(i, parquetRow.getLong(columnIndex).toInt)
              }
            case (Type.DOUBLE) => {
              val d = parquetRow.getDouble(columnIndex)
              row.setDouble(i, d)
            
            }
            case (Type.BOOLEAN) => row.setBoolean(i, parquetRow.getBoolean(columnIndex))
            case (Type.DATETIME) => {
              val dt = java.util.Calendar.getInstance
              dt.setTime(new java.util.Date(parquetRow.getLong(columnIndex)))
              row.setDateTime(i, dt.get(java.util.Calendar.YEAR),
                                 dt.get(java.util.Calendar.MONTH) + 1,
                                 dt.get(java.util.Calendar.DAY_OF_MONTH),
                                 dt.get(java.util.Calendar.HOUR_OF_DAY),
                                 dt.get(java.util.Calendar.MINUTE),
                                 dt.get(java.util.Calendar.SECOND),
                                 dt.get(java.util.Calendar.MILLISECOND))
            }
            case _ =>
          }
        }
    }
    row
  }
}