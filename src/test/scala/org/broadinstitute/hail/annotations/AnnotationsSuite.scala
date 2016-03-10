package org.broadinstitute.hail.annotations

import org.apache.spark.sql.types._
import org.broadinstitute.hail.SparkSuite
import org.broadinstitute.hail.Utils._
import org.broadinstitute.hail.expr
import org.broadinstitute.hail.driver._
import org.broadinstitute.hail.variant.{GenotypeStream, Genotype, IntervalList, Variant}
import org.testng.annotations.Test
import org.broadinstitute.hail.methods._
import scala.collection.mutable
import scala.language.implicitConversions

/**
  * This testing suite evaluates the functionality of the [[org.broadinstitute.hail.annotations]] package
  */
class AnnotationsSuite extends SparkSuite {
  @Test def test() {
    /*
      The below tests are designed to check for a subset of variants and info fields, that:
          1. the types, emitConversionIdentifier strings, and description strings agree with the VCF
          2. the strings stored in the AnnotationData classes agree with the VCF
          3. the strings stored in the AnnotationData classes convert correctly to the proper type
    */

    val vds = LoadVCF(sc, "src/test/resources/sample.vcf")

    val state = State(sc, sqlContext, vds)
    val vas = vds.vaSignatures
    val variantAnnotationMap = vds.variantsAndAnnotations.collect().toMap

    val firstVariant = Variant("20", 10019093, "A", "G")
    val anotherVariant = Variant("20", 10036107, "T", "G")
    assert(variantAnnotationMap.contains(firstVariant))
    assert(variantAnnotationMap.contains(anotherVariant))

    // type Int - info.DP
    val dpQuery = vas.query("info", "DP")
    assert(vas.getOption(List("info", "DP")).contains(VCFSignature(expr.TInt, "Integer", "1",
      "Approximate read depth; some reads may have been filtered")))
    assert(dpQuery(variantAnnotationMap(firstVariant))
      .get == 77560)
    assert(dpQuery(variantAnnotationMap(anotherVariant))
      .get == 20271)

    // type Double - info.HWP
    val hwpQuery = vas.query("info", "HWP")
    assert(vas.getOption(List("info", "HWP")).contains(new VCFSignature(expr.TDouble, "Float", "1",
      "P value from test of Hardy Weinberg Equilibrium")))
    assert(
      D_==(hwpQuery(variantAnnotationMap(firstVariant))
        .get.asInstanceOf[Double], 0.0001))
    assert(D_==(hwpQuery(variantAnnotationMap(anotherVariant))
      .get.asInstanceOf[Double], 0.8286))

    // type String - info.culprit
    val culpritQuery = vas.query("info", "culprit")
    assert(vas.getOption(List("info", "culprit")).contains(VCFSignature(expr.TString, "String", "1",
      "The annotation which was the worst performing in the Gaussian mixture model, " +
        "likely the reason why the variant was filtered out")))
    assert(culpritQuery(variantAnnotationMap(firstVariant))
      .contains("FS"))
    assert(culpritQuery(variantAnnotationMap(anotherVariant))
      .contains("FS"))

    // type Array - info.AC (allele count)
    val acQuery = vas.query("info", "AC")
    assert(vas.getOption(List("info", "AC")).contains(VCFSignature(expr.TArray(expr.TInt), "Integer", "A",
      "Allele count in genotypes, for each ALT allele, in the same order as listed")))
    assert(acQuery(variantAnnotationMap(firstVariant))
      .contains(Array(89): mutable.WrappedArray[Int]))
    assert(acQuery(variantAnnotationMap(anotherVariant))
      .contains(Array(13): mutable.WrappedArray[Int]))

    // type Boolean/flag - info.DB (dbSNP membership)
    val dbQuery = vas.query("info", "DB")
    assert(vas.getOption(List("info", "DB")).contains(new VCFSignature(expr.TBoolean, "Flag", "0",
      "dbSNP Membership")))
    assert(dbQuery(variantAnnotationMap(firstVariant))
      .contains(true))
    assert(dbQuery(variantAnnotationMap(anotherVariant))
      .isEmpty)

    //type Set[String]
    val filtQuery = vas.query("filters")
    assert(vas.getOption(List("filters")).contains(new SimpleSignature(expr.TSet(expr.TString))))
    assert(filtQuery(variantAnnotationMap(firstVariant))
      .contains(Array("PASS"): mutable.WrappedArray[String]))
    assert(filtQuery(variantAnnotationMap(anotherVariant))
      contains (Array("VQSRTrancheSNP99.95to100.00"): mutable.WrappedArray[String]))

    // GATK PASS
    val passQuery = vas.query("pass")
    assert(vas.getOption(List("pass")).contains(new SimpleSignature(expr.TBoolean)))
    assert(passQuery(variantAnnotationMap(firstVariant))
      .contains(true))
    assert(passQuery(variantAnnotationMap(anotherVariant))
      .contains(false))

    val vds2 = LoadVCF(sc, "src/test/resources/sample2.vcf")
    val vas2 = vds2.vaSignatures

    // Check that VDS can be written to disk and retrieved while staying the same
    hadoopDelete("/tmp/sample.vds", sc.hadoopConfiguration, recursive = true)
    vds2.write(sqlContext, "/tmp/sample.vds")
    val readBack = Read.run(state, Array("-i", "/tmp/sample.vds"))

    assert(readBack.vds.same(vds2))
  }

  @Test def testReadWrite() {
    val vds1 = LoadVCF(sc, "src/test/resources/sample.vcf")
    val s = State(sc, sqlContext, vds1)
    val vds2 = LoadVCF(sc, "src/test/resources/sample.vcf")
    assert(vds1.same(vds2))
    Write.run(s, Array("-o", "/tmp/sample.vds"))
    val vds3 = Read.run(s, Array("-i", "/tmp/sample.vds")).vds
    assert(vds3.same(vds1))
  }

  @Test def testAnnotationOperations() {

    /*
      This test method performs a number of annotation operations on a vds, and ensures that the signatures
      and annotations in the RDD elements are what is expected after each step.  In particular, we want to
      test overwriting behavior, deleting, appending, and querying.
    */

    var vds = LoadVCF(sc, "src/test/resources/sample.vcf")

    // clear everything
    val (emptyS, d1) = vds.deleteVA()
    vds = vds.mapAnnotations((v, va) => d1(va))
      .copy(vaSignatures = emptyS)
    assert(emptyS == Annotation.emptySignature)

    // add to the first layer
    val toAdd = 5
    val toAddSig = SimpleSignature(expr.TInt)
    val (s1, i1) = vds.vaSignatures.insert(List("I1"), toAddSig)
    vds = vds.mapAnnotations((v, va) => i1(va, Some(toAdd)))
      .copy(vaSignatures = s1)
    assert(vds.vaSignatures.getSchema ==
      StructType(Array(StructField("I1", IntegerType, true))))

    val q1 = vds.queryVA("I1")
    assert(vds.rdd
      .collect()
      .forall { case (v, va, gs) => q1(va) == Some(5) })

    // add another to the first layer
    val toAdd2 = "test"
    val toAdd2Sig = SimpleSignature(expr.TString)
    val (s2, i2) = vds.vaSignatures.insert(List("S1"), toAdd2Sig)
    vds = vds.mapAnnotations((v, va) => i2(va, Some(toAdd2)))
      .copy(vaSignatures = s2)
    assert(vds.vaSignatures.getSchema ==
      StructType(Array(StructField("I1", IntegerType, true), StructField("S1", StringType, true))))

    val q2 = vds.queryVA("S1")
    assert(vds.rdd
      .collect()
      .forall { case (v, va, gs) => q2(va) == Some("test") })

    // overwrite I1 with a row in the second layer
    val toAdd3 = Annotation(1, 3)
    val toAdd3Sig = StructSignature(Map(
      "I2" ->(0, SimpleSignature(expr.TInt)),
      "I3" ->(1, SimpleSignature(expr.TInt))))
    val (s3, i3) = vds.vaSignatures.insert(List("I1"), toAdd3Sig)
    vds = vds.mapAnnotations((v, va) => i3(va, Some(toAdd3)))
      .copy(vaSignatures = s3)
    assert(vds.vaSignatures.getSchema ==
      StructType(Array(StructField("I1", toAdd3Sig.getSchema, true), StructField("S1", StringType, true))))

    val q3 = vds.queryVA("I1")
    val q4 = vds.queryVA("I1", "I2")
    val q5 = vds.queryVA("I1", "I3")
    assert(vds.rdd
      .collect()
      .forall { case (v, va, gs) =>
        (q3(va) == Some(Annotation(1, 3))) &&
          (q4(va) == Some(1)) &&
          (q5(va) == Some(3))
      })

    // add something deep in the tree with an unbuilt structure
    val toAdd4 = "dummy"
    val toAdd4Sig = SimpleSignature(expr.TString)
    val (s4, i4) = vds.insertVA(toAdd4Sig, "a", "b", "c", "d", "e")
    vds = vds.mapAnnotations((v, va) => i4(va, Some(toAdd4)))
      .copy(vaSignatures = s4)
    assert(vds.vaSignatures.getSchema ==
      StructType(Array(
        StructField("I1", toAdd3Sig.getSchema, true),
        StructField("S1", StringType, true),
        StructField("a", StructType(Array(
          StructField("b", StructType(Array(
            StructField("c", StructType(Array(
              StructField("d", StructType(Array(
                StructField("e", StringType, true))),
                true))),
              true))),
            true))),
          true))))

    val q6 = vds.queryVA("a", "b", "c", "d", "e")
    assert(vds.rdd
      .collect()
      .forall { case (v, va, gs) => q6(va) == Some("dummy") })

    // add something as a sibling deep in the tree
    val toAdd5 = "dummy2"
    val toAdd5Sig = SimpleSignature(expr.TString)
    val (s5, i5) = vds.insertVA(toAdd5Sig, "a", "b", "c", "f")
    vds = vds.mapAnnotations((v, va) => i5(va, Some(toAdd5)))
      .copy(vaSignatures = s5)

    assert(vds.vaSignatures.getSchema ==
      StructType(Array(
        StructField("I1", toAdd3Sig.getSchema, true),
        StructField("S1", StringType, true),
        StructField("a", StructType(Array(
          StructField("b", StructType(Array(
            StructField("c", StructType(Array(
              StructField("d", StructType(Array(
                StructField("e", StringType, true))),
                true),
              StructField("f", StringType, true))),
              true))),
            true))),
          true))))
    val q7 = vds.queryVA("a", "b", "c", "f")
    assert(vds.rdd
      .collect()
      .forall { case (v, va, gs) => q7(va) == Some("dummy2") })

    // overwrite something deep in the tree
    val toAdd6 = "dummy3"
    val toAdd6Sig = SimpleSignature(expr.TString)
    val (s6, i6) = vds.insertVA(toAdd6Sig, "a", "b", "c", "d")
    vds = vds.mapAnnotations((v, va) => i6(va, Some(toAdd6)))
      .copy(vaSignatures = s6)

    assert(vds.vaSignatures.getSchema ==
      StructType(Array(
        StructField("I1", toAdd3Sig.getSchema, true),
        StructField("S1", StringType, true),
        StructField("a", StructType(Array(
          StructField("b", StructType(Array(
            StructField("c", StructType(Array(
              StructField("d", StringType, true),
              StructField("f", StringType, true))),
              true))),
            true))),
          true))))

    val q8 = vds.queryVA("a", "b", "c", "d")
    assert(vds.rdd
      .collect()
      .forall { case (v, va, gs) => q8(va) == Some("dummy3") })

    // delete that part of the tree
    val (s7, d2) = vds.deleteVA("a")
    vds = vds.mapAnnotations((v, va) => d2(va))
      .copy(vaSignatures = s7)

    assert(vds.vaSignatures.getSchema ==
      StructType(Array(
        StructField("I1", toAdd3Sig.getSchema, true),
        StructField("S1", StringType, true))))

    assert(vds.rdd
      .collect()
      .forall { case (v, va, gs) => va == Annotation(toAdd3, "test") })

    // delete the first thing in the row and make sure things are shifted over correctly
    val (s8, d3) = vds.deleteVA("I1")
    vds = vds.mapAnnotations((v, va) => d3(va))
      .copy(vaSignatures = s8)

    assert(vds.vaSignatures.getSchema ==
      StructType(Array(
        StructField("S1", StringType, true))))
    assert(vds.rdd
      .collect()
      .forall { case (v, va, gs) => va == Annotation("test") })

    // remap the head
    val toAdd7 = "dummy"
    val toAdd7Sig = SimpleSignature(expr.TString)
    val (s9, i7) = vds.insertVA(toAdd7Sig, List[String]())
    vds = vds.mapAnnotations((v, va) => i7(va, Some(toAdd7)))
      .copy(vaSignatures = s9)

    assert(vds.vaSignatures.getSchema == toAdd7Sig.getSchema)
    assert(vds.rdd.collect()
      .forall { case (v, va, gs) => va == "dummy" })
  }
}
