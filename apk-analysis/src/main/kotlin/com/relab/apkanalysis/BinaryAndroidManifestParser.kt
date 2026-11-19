package com.relab.apkanalysis
import java.nio.ByteBuffer
import java.nio.ByteOrder
internal data class DecodedManifest(val packageName:String?,val versionCode:String?,val versionName:String?,val permissions:List<String>,val components:List<ApkComponent>)
/** Bounds-checked reader for Android binary XML manifest chunks. */
internal object BinaryAndroidManifestParser {
 private const val STRING_POOL=1; private const val START_ELEMENT=0x0102; private const val END_ELEMENT=0x0103
 private const val UTF8_FLAG=0x100; private const val TYPE_STRING=3; private const val TYPE_INT_DEC=0x10; private const val TYPE_INT_HEX=0x11; private const val TYPE_INT_BOOLEAN=0x12
 fun parse(bytes:ByteArray):DecodedManifest {
  val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
  require(bytes.size>=8 && u16(b,0)==3){"Not Android binary XML."}
  val total=u32(b,4); require(total in 8..bytes.size){"Invalid binary XML size."}
  val strings=mutableListOf<String>(); val permissions=mutableListOf<String>(); val components=mutableListOf<ApkComponent>()
  var pkg:String?=null; var code:String?=null; var version:String?=null; var off=u16(b,2)
  while(off+8<=total){
   val type=u16(b,off); val header=u16(b,off+2); val size=u32(b,off+4)
   require(header>=8 && size>=header && off+size<=total){"Malformed binary XML chunk."}
   when(type){
    STRING_POOL->strings.addAll(readPool(b,off,header,size))
    START_ELEMENT->{
     require(header>=16 && size>=36){"Malformed element."}
     val ext=off+16; val tag=str(strings,u32(b,ext+4)); val attrStart=u16(b,ext+8); val attrSize=u16(b,ext+10); val count=u16(b,ext+12)
     require(attrSize>=20 && ext+attrStart+count*attrSize<=off+size){"Malformed attributes."}
     val attrs=linkedMapOf<String,String>()
     repeat(count){i->
      val p=ext+attrStart+i*attrSize; val name=str(strings,u32(b,p+4)); val raw=b.getInt(p+8); val valueType=b.get(p+15).toInt() and 255; val data=b.getInt(p+16)
      val value=when {raw>=0->str(strings,raw); valueType==TYPE_STRING->str(strings,data); valueType==TYPE_INT_BOOLEAN->(data!=0).toString(); valueType==TYPE_INT_DEC->data.toString(); valueType==TYPE_INT_HEX->"0x"+data.toUInt().toString(16); else->data.toString()}
      attrs[name]=value
     }
     when(tag){
      "manifest"->{pkg=attrs["package"];code=attrs["versionCode"];version=attrs["versionName"]}
      "uses-permission","uses-permission-sdk-23","uses-permission-sdk-m"->attrs["name"]?.takeIf{it.isNotBlank()}?.let(permissions::add)
      "activity","activity-alias","service","receiver","provider"->attrs["name"]?.takeIf{it.isNotBlank()}?.let{components+=ApkComponent(tag,it,attrs["exported"]?.toBooleanStrictOrNull(),attrs["permission"])}
     }
    }
    END_ELEMENT->Unit
   }
   off+=size
  }
  require(off==total){"Incomplete binary XML."}
  return DecodedManifest(pkg,code,version,permissions.distinct(),components)
 }
 private fun readPool(b:ByteBuffer,s:Int,h:Int,n:Int):List<String>{
  require(h>=28){"Malformed string pool."}; val count=u32(b,s+8); val flags=u32(b,s+16); val start=u32(b,s+20)
  require(count<=200000 && s+h+count*4<=s+n){"Invalid string pool count."}
  return (0 until count).map{i->
   var p=s+start+u32(b,s+h+i*4); val end=s+n
   if(flags and UTF8_FLAG!=0){
    p=length8(b,p,end).second; val (len,next)=length8(b,p,end); p=next
    require(p+len<end){"Invalid UTF-8 string."}; val bytes=ByteArray(len); val d=b.duplicate();d.position(p);d.get(bytes);String(bytes,Charsets.UTF_8)
   }else{
    val(len,next)=length16(b,p,end);p=next;require(p+len*2<=end){"Invalid UTF-16 string."}
    String(CharArray(len){b.getShort(p+it*2).toInt().toChar()})
   }
  }
 }
 private fun length8(b:ByteBuffer,p:Int,end:Int):Pair<Int,Int>{require(p<end){"Truncated string."};val a=b.get(p).toInt() and 255;return if(a and 128==0)a to p+1 else {require(p+1<end);(((a and 127) shl 8) or (b.get(p+1).toInt() and 255)) to p+2}}
 private fun length16(b:ByteBuffer,p:Int,end:Int):Pair<Int,Int>{require(p+2<=end){"Truncated string."};val a=u16(b,p);return if(a and 0x8000==0)a to p+2 else {require(p+4<=end);(((a and 0x7fff) shl 16) or u16(b,p+2)) to p+4}}
 private fun str(s:List<String>,i:Int):String{require(i in s.indices){"Invalid string index."};return s[i]}
 private fun u16(b:ByteBuffer,p:Int)=b.getShort(p).toInt() and 65535
 private fun u32(b:ByteBuffer,p:Int):Int{val v=b.getInt(p);require(v>=0){"Oversized binary XML field."};return v}
}