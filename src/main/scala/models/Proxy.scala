package models

import slick.lifted.Tag
import slick.jdbc.PostgresProfile.api._

case class Proxy(IP:String,port:String,TTL:Byte,protocol:String)

class ProxyTable(tag: Tag) extends Table[Proxy](tag,"Proxy"){
  val IP = column[String]("IP")
  val port = column[String]("port")
  val TTL = column[Byte]("TTL")
  val protocol = column[String]("protocol")

  def * = (IP,port,TTL,protocol) <> (Proxy.apply _ tupled,Proxy.unapply)
}

object ProxyTable {
  val table = TableQuery[ProxyTable]
}