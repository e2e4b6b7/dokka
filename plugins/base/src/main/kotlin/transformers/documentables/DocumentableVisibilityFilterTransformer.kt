package org.jetbrains.dokka.base.transformers.documentables

import org.jetbrains.dokka.DokkaConfiguration
import org.jetbrains.dokka.model.*
import org.jetbrains.dokka.plugability.DokkaContext
import org.jetbrains.dokka.transformers.documentation.PreMergeDocumentableTransformer
import org.jetbrains.dokka.DokkaConfiguration.DokkaSourceSet
import org.jetbrains.dokka.DokkaDefaults

class DocumentableVisibilityFilterTransformer(val context: DokkaContext) : PreMergeDocumentableTransformer {

    override fun invoke(modules: List<DModule>) = modules.map { original ->
        val sourceSet = original.sourceSets.single()
        val packageOptions = sourceSet.perPackageOptions
        DocumentableVisibilityFilter(packageOptions, sourceSet).processModule(original)
    }

    private class DocumentableVisibilityFilter(
        val packageOptions: List<DokkaConfiguration.PackageOptions>,
        val globalOptions: DokkaSourceSet
    ) {
        fun Visibility.isAllowedInPackage(packageName: String?) = when (this) {
            is JavaVisibility.Public,
            is KotlinVisibility.Public -> isAllowedInPackage(packageName, DokkaConfiguration.Visibility.PUBLIC)
            is JavaVisibility.Private,
            is KotlinVisibility.Private -> isAllowedInPackage(packageName, DokkaConfiguration.Visibility.PRIVATE)
            is JavaVisibility.Protected,
            is KotlinVisibility.Protected -> isAllowedInPackage(packageName, DokkaConfiguration.Visibility.PROTECTED)
            is KotlinVisibility.Internal -> isAllowedInPackage(packageName, DokkaConfiguration.Visibility.INTERNAL)
            is JavaVisibility.Default -> isAllowedInPackage(packageName, DokkaConfiguration.Visibility.PACKAGE)
        }

        private fun isAllowedInPackage(packageName: String?, visibility: DokkaConfiguration.Visibility): Boolean {
            val packageOpts = packageName.takeIf { it != null }?.let { name ->
                packageOptions.firstOrNull { Regex(it.matchingRegex).matches(name) }
            }

            val (documentedVisibilities, includeNonPublic) = when {
                packageOpts != null -> packageOpts.documentedVisibilities to packageOpts.includeNonPublic
                else -> globalOptions.documentedVisibilities to globalOptions.includeNonPublic
            }

            // if `documentedVisibilities` is explicitly overridden by the user (i.e. not default value by reference),
            // deprecated `includeNonPublic` should not be taken into account, so that only one setting prevails
            val isDocumentedVisibilitiesOverridden = documentedVisibilities !== DokkaDefaults.documentedVisibilities
            return documentedVisibilities.contains(visibility) || (!isDocumentedVisibilitiesOverridden && includeNonPublic)
        }

        fun processModule(original: DModule) =
            filterPackages(original.packages).let { (modified, packages) ->
                if (!modified) original
                else
                    DModule(
                        original.name,
                        packages = packages,
                        documentation = original.documentation,
                        sourceSets = original.sourceSets,
                        extra = original.extra
                    )
            }


        private fun filterPackages(packages: List<DPackage>): Pair<Boolean, List<DPackage>> {
            var packagesListChanged = false
            val filteredPackages = packages.map {
                var modified = false
                val functions = filterFunctions(it.functions).let { (listModified, list) ->
                    modified = modified || listModified
                    list
                }
                val properties = filterProperties(it.properties).let { (listModified, list) ->
                    modified = modified || listModified
                    list
                }
                val classlikes = filterClasslikes(it.classlikes).let { (listModified, list) ->
                    modified = modified || listModified
                    list
                }
                val typeAliases = filterTypeAliases(it.typealiases).let { (listModified, list) ->
                    modified = modified || listModified
                    list
                }
                when {
                    !modified -> it
                    else -> {
                        packagesListChanged = true
                        DPackage(
                            it.dri,
                            functions,
                            properties,
                            classlikes,
                            typeAliases,
                            it.documentation,
                            it.expectPresentInSet,
                            it.sourceSets,
                            it.extra
                        )
                    }
                }
            }
            return Pair(packagesListChanged, filteredPackages)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun <T : WithVisibility> alwaysTrue(a: T, p: DokkaSourceSet) = true
        @Suppress("UNUSED_PARAMETER")
        private fun <T : WithVisibility> alwaysFalse(a: T, p: DokkaSourceSet) = false
        @Suppress("UNUSED_PARAMETER")
        private fun <T> alwaysNoModify(a: T, sourceSets: Set<DokkaSourceSet>) = false to a

        private fun WithVisibility.visibilityForPlatform(data: DokkaSourceSet): Visibility? = visibility[data]

        private fun <T> T.filterPlatforms(
            additionalCondition: (T, DokkaSourceSet) -> Boolean = ::alwaysTrue,
            alternativeCondition: (T, DokkaSourceSet) -> Boolean = ::alwaysFalse
        ) where T : Documentable, T : WithVisibility =
            sourceSets.filter { d ->
                visibilityForPlatform(d)?.isAllowedInPackage(dri.packageName) == true &&
                        additionalCondition(this, d) ||
                        alternativeCondition(this, d)
            }.toSet()

        private fun <T> List<T>.transform(
            additionalCondition: (T, DokkaSourceSet) -> Boolean = ::alwaysTrue,
            alternativeCondition: (T, DokkaSourceSet) -> Boolean = ::alwaysFalse,
            modify: (T, Set<DokkaSourceSet>) -> Pair<Boolean, T> = ::alwaysNoModify,
            recreate: (T, Set<DokkaSourceSet>) -> T,
        ): Pair<Boolean, List<T>> where T : Documentable, T : WithVisibility {
            var changed = false
            val values = mapNotNull { t ->
                val filteredPlatforms = t.filterPlatforms(additionalCondition, alternativeCondition)
                when (filteredPlatforms.size) {
                    t.visibility.size -> {
                        val (wasChanged, element) = modify(t, filteredPlatforms)
                        changed = changed || wasChanged
                        element
                    }
                    0 -> {
                        changed = true
                        null
                    }
                    else -> {
                        changed = true
                        recreate(t, filteredPlatforms)
                    }
                }
            }
            return Pair(changed, values)
        }

        private fun filterFunctions(
            functions: List<DFunction>,
            additionalCondition: (DFunction, DokkaSourceSet) -> Boolean = ::alwaysTrue
        ) =
            functions.transform(additionalCondition) { original, filteredPlatforms ->
                with(original) {
                    copy(
                        documentation = documentation.filtered(filteredPlatforms),
                        expectPresentInSet = expectPresentInSet.filtered(filteredPlatforms),
                        sources = sources.filtered(filteredPlatforms),
                        visibility = visibility.filtered(filteredPlatforms),
                        generics = generics.mapNotNull { it.filter(filteredPlatforms) },
                        sourceSets = filteredPlatforms,
                    )
                }
            }

        private fun hasVisibleAccessorsForPlatform(property: DProperty, data: DokkaSourceSet) =
            property.getter?.visibilityForPlatform(data)?.isAllowedInPackage(property.dri.packageName) == true ||
                    property.setter?.visibilityForPlatform(data)?.isAllowedInPackage(property.dri.packageName) == true

        private fun filterProperties(
            properties: List<DProperty>,
            additionalCondition: (DProperty, DokkaSourceSet) -> Boolean = ::alwaysTrue,
            additionalConditionAccessors: (DFunction, DokkaSourceSet) -> Boolean = ::alwaysTrue
        ): Pair<Boolean, List<DProperty>> {

            val modifier: (DProperty, Set<DokkaSourceSet>) -> Pair<Boolean, DProperty> =
                { original, filteredPlatforms ->
                    val setter = original.setter?.let { filterFunctions(listOf(it), additionalConditionAccessors) }
                    val getter = original.getter?.let { filterFunctions(listOf(it), additionalConditionAccessors) }

                    val modified = setter?.first == true || getter?.first == true

                    val property =
                        if (modified)
                            original.copy(
                                setter = setter?.second?.firstOrNull(),
                                getter = getter?.second?.firstOrNull()
                            )
                        else original
                    modified to property
                }

            return properties.transform(
                additionalCondition,
                ::hasVisibleAccessorsForPlatform,
                modifier
            ) { original, filteredPlatforms ->
                val setter = original.setter?.let { filterFunctions(listOf(it), additionalConditionAccessors) }
                val getter = original.getter?.let { filterFunctions(listOf(it), additionalConditionAccessors) }

                with(original) {
                    copy(
                        documentation = documentation.filtered(filteredPlatforms),
                        expectPresentInSet = expectPresentInSet.filtered(filteredPlatforms),
                        sources = sources.filtered(filteredPlatforms),
                        visibility = visibility.filtered(filteredPlatforms),
                        sourceSets = filteredPlatforms,
                        generics = generics.mapNotNull { it.filter(filteredPlatforms) },
                        setter = setter?.second?.firstOrNull(),
                        getter = getter?.second?.firstOrNull()
                    )
                }
            }
        }

        private fun filterEnumEntries(entries: List<DEnumEntry>, filteredPlatforms: Set<DokkaSourceSet>): Pair<Boolean, List<DEnumEntry>> =
            entries.foldRight(Pair(false, emptyList())) { entry, acc ->
                val intersection = filteredPlatforms.intersect(entry.sourceSets)
                if (intersection.isEmpty()) Pair(true, acc.second)
                else {
                    val functions = filterFunctions(entry.functions) { _, data -> data in intersection }
                    val properties = filterProperties(entry.properties) { _, data -> data in intersection }
                    val classlikes = filterClasslikes(entry.classlikes) { _, data -> data in intersection }

                    DEnumEntry(
                        entry.dri,
                        entry.name,
                        entry.documentation.filtered(intersection),
                        entry.expectPresentInSet.filtered(filteredPlatforms),
                        functions.second,
                        properties.second,
                        classlikes.second,
                        intersection,
                        entry.extra
                    ).let { Pair(functions.first || properties.first || classlikes.first, acc.second + it) }
                }
            }

        private fun filterClasslikes(
            classlikeList: List<DClasslike>,
            additionalCondition: (DClasslike, DokkaSourceSet) -> Boolean = ::alwaysTrue
        ): Pair<Boolean, List<DClasslike>> {
            var classlikesListChanged = false
            val filteredClasslikes: List<DClasslike> = classlikeList.mapNotNull {
                with(it) {
                    val filteredPlatforms = filterPlatforms(additionalCondition)
                    if (filteredPlatforms.isEmpty()) {
                        classlikesListChanged = true
                        null
                    } else {
                        var modified = sourceSets.size != filteredPlatforms.size
                        val functions =
                            filterFunctions(functions) { _, data -> data in filteredPlatforms }.let { (listModified, list) ->
                                modified = modified || listModified
                                list
                            }
                        val properties =
                            filterProperties(properties) { _, data -> data in filteredPlatforms }.let { (listModified, list) ->
                                modified = modified || listModified
                                list
                            }
                        val classlikes =
                            filterClasslikes(classlikes) { _, data -> data in filteredPlatforms }.let { (listModified, list) ->
                                modified = modified || listModified
                                list
                            }
                        val companion =
                            if (this is WithCompanion) filterClasslikes(listOfNotNull(companion)) { _, data -> data in filteredPlatforms }.let { (listModified, list) ->
                                modified = modified || listModified
                                list.firstOrNull() as DObject?
                            } else null
                        val constructors = if (this is WithConstructors)
                            filterFunctions(constructors) { _, data -> data in filteredPlatforms }.let { (listModified, list) ->
                                modified = modified || listModified
                                list
                            } else emptyList()
                        val generics =
                            if (this is WithGenerics) generics.mapNotNull { param -> param.filter(filteredPlatforms) } else emptyList()
                        val enumEntries =
                            if (this is DEnum) filterEnumEntries(entries, filteredPlatforms).let { (listModified, list) ->
                                modified = modified || listModified
                                list
                            } else emptyList()
                        classlikesListChanged = classlikesListChanged || modified
                        when {
                            !modified -> this
                            this is DClass -> copy(
                                constructors = constructors,
                                functions = functions,
                                properties = properties,
                                classlikes = classlikes,
                                sources = sources.filtered(filteredPlatforms),
                                visibility = visibility.filtered(filteredPlatforms),
                                companion = companion,
                                generics = generics,
                                supertypes = supertypes.filtered(filteredPlatforms),
                                documentation = documentation.filtered(filteredPlatforms),
                                expectPresentInSet = expectPresentInSet.filtered(filteredPlatforms),
                                sourceSets = filteredPlatforms
                            )
                            this is DAnnotation -> copy(
                                documentation = documentation.filtered(filteredPlatforms),
                                expectPresentInSet = expectPresentInSet.filtered(filteredPlatforms),
                                sources = sources.filtered(filteredPlatforms),
                                functions = functions,
                                properties = properties,
                                classlikes = classlikes,
                                visibility = visibility.filtered(filteredPlatforms),
                                companion = companion,
                                constructors = constructors,
                                generics = generics,
                                sourceSets = filteredPlatforms
                            )
                            this is DEnum -> copy(
                                entries = enumEntries,
                                documentation = documentation.filtered(filteredPlatforms),
                                expectPresentInSet = expectPresentInSet.filtered(filteredPlatforms),
                                sources = sources.filtered(filteredPlatforms),
                                functions = functions,
                                properties = properties,
                                classlikes = classlikes,
                                visibility = visibility.filtered(filteredPlatforms),
                                companion = companion,
                                constructors = constructors,
                                supertypes = supertypes.filtered(filteredPlatforms),
                                sourceSets = filteredPlatforms
                            )
                            this is DInterface -> copy(
                                documentation = documentation.filtered(filteredPlatforms),
                                expectPresentInSet = expectPresentInSet.filtered(filteredPlatforms),
                                sources = sources.filtered(filteredPlatforms),
                                functions = functions,
                                properties = properties,
                                classlikes = classlikes,
                                visibility = visibility.filtered(filteredPlatforms),
                                companion = companion,
                                generics = generics,
                                supertypes = supertypes.filtered(filteredPlatforms),
                                sourceSets = filteredPlatforms
                            )
                            this is DObject -> copy(
                                documentation = documentation.filtered(filteredPlatforms),
                                expectPresentInSet = expectPresentInSet.filtered(filteredPlatforms),
                                sources = sources.filtered(filteredPlatforms),
                                functions = functions,
                                properties = properties,
                                classlikes = classlikes,
                                supertypes = supertypes.filtered(filteredPlatforms),
                                sourceSets = filteredPlatforms
                            )
                            else -> null
                        }
                    }
                }
            }
            return Pair(classlikesListChanged, filteredClasslikes)
        }

        private fun filterTypeAliases(
            typeAliases: List<DTypeAlias>,
            additionalCondition: (DTypeAlias, DokkaSourceSet) -> Boolean = ::alwaysTrue
        ) =
            typeAliases.transform(additionalCondition) { original, filteredPlatforms ->
                with(original) {
                    copy(
                        documentation = documentation.filtered(filteredPlatforms),
                        expectPresentInSet = expectPresentInSet.filtered(filteredPlatforms),
                        underlyingType = underlyingType.filtered(filteredPlatforms),
                        visibility = visibility.filtered(filteredPlatforms),
                        generics = generics.mapNotNull { it.filter(filteredPlatforms) },
                        sourceSets = filteredPlatforms,
                    )
                }
            }
    }
}
