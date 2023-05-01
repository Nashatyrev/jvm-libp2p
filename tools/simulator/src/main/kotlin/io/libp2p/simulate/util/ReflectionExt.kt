package io.libp2p.simulate.util

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class InlineProperties()

fun Any.propertiesAsMap(onlyConstructorParams: Boolean = false) =
    this.javaClass.kotlin
        .propertiesRecursively(onlyConstructorParams)
        .associate {
            it.name to it.get(this)
        }

data class ExtPropertyDescriptor(
    val property: KProperty1<Any, *>,
    val targetObjExtractor: (Any) -> Any = { it }
) {
    val name get() = property.name

    fun get(obj: Any): Any =
        property.get(targetObjExtractor(obj))!!
}

@Suppress("UNCHECKED_CAST")
fun KClass<Any>.propertiesRecursively(onlyConstructorParams: Boolean = false): List<ExtPropertyDescriptor> {
    val properties = this.properties(onlyConstructorParams)
    return properties.flatMap { prop ->
        if (prop.findAnnotation<InlineProperties>() != null) {
            val childKlass = prop.returnType.classifier as KClass<Any>
            childKlass.propertiesRecursively(onlyConstructorParams)
                .map { propDescr->
                    ExtPropertyDescriptor(propDescr.property) { obj ->
                        val chilPObj = prop.get(obj)!!
                        propDescr.targetObjExtractor(chilPObj)
                    }
                }
        } else {
            listOf(ExtPropertyDescriptor(prop))
        }
    }
}

fun KClass<Any>.properties(onlyConstructorParams: Boolean = false): List<KProperty1<Any, *>> {
    val params = this.primaryConstructor?.parameters ?: emptyList()
    val paramByNames = params.withIndex().associateBy { it.value.name ?: "" }

    val sortedParamProperties =
        memberProperties
            .filter { it.name in paramByNames }
            .sortedBy { paramByNames[it.name]!!.index }
    val otherProperties =
        if (onlyConstructorParams) {
            emptyList()
        } else {
            memberProperties
                .filter { it.name !in paramByNames }
        }
    return sortedParamProperties + otherProperties
}

