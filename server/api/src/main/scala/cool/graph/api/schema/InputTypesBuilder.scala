package cool.graph.api.schema

import cool.graph.cache.Cache
import cool.graph.shared.models.{Field, Model, Project, Relation}
import sangria.schema.{InputField, InputObjectType, InputType, ListInputType, OptionInputType}

trait InputTypesBuilder {
  def inputObjectTypeForCreate(model: Model, omitRelation: Option[Relation] = None): InputObjectType[Any]

  def inputObjectTypeForUpdate(model: Model): InputObjectType[Any]

  def inputObjectTypeForWhere(model: Model): Option[InputObjectType[Any]]
}

case class CachedInputTypesBuilder(project: Project) extends UncachedInputTypesBuilder(project) {
  import java.lang.{StringBuilder => JStringBuilder}

  val cache = Cache.unbounded[String, InputObjectType[Any]]()

  override def inputObjectTypeForCreate(model: Model, omitRelation: Option[Relation]): InputObjectType[Any] = {
    cache.getOrUpdate(cacheKey("cachedInputObjectTypeForCreate", model, omitRelation), { () =>
      computeInputObjectTypeForCreate(model, omitRelation)
    })
  }

  override def inputObjectTypeForUpdate(model: Model): InputObjectType[Any] = {
    cache.getOrUpdate(cacheKey("cachedInputObjectTypeForUpdate", model), { () =>
      computeInputObjectTypeForUpdate(model)
    })
  }

  private def cacheKey(name: String, model: Model, relation: Option[Relation] = None): String = {
    val sb = new JStringBuilder()
    sb.append(name)
    sb.append(model.id)
    sb.append(relation.orNull)
    sb.toString
  }
}

abstract class UncachedInputTypesBuilder(project: Project) extends InputTypesBuilder {
  override def inputObjectTypeForCreate(model: Model, omitRelation: Option[Relation]): InputObjectType[Any] = {
    computeInputObjectTypeForCreate(model, omitRelation)
  }

  override def inputObjectTypeForUpdate(model: Model): InputObjectType[Any] = {
    computeInputObjectTypeForUpdate(model)
  }

  override def inputObjectTypeForWhere(model: Model): Option[InputObjectType[Any]] = {
    computeInputObjectTypeForWhere(model)
  }

  protected def computeInputObjectTypeForCreate(model: Model, omitRelation: Option[Relation]): InputObjectType[Any] = {
    val inputObjectTypeName = omitRelation match {
      case None =>
        s"${model.name}CreateInput"

      case Some(relation) =>
        val field = relation.getField_!(project, model)
        s"${model.name}CreateWithout${field.name.capitalize}Input"
    }

    InputObjectType[Any](
      name = inputObjectTypeName,
      fieldsFn = () => {
        computeScalarInputFieldsForCreate(model) ++ computeRelationalInputFieldsForCreate(model, omitRelation)
      }
    )
  }

  protected def computeInputObjectTypeForUpdate(model: Model): InputObjectType[Any] = {
    InputObjectType[Any](
      name = s"${model.name}UpdateInput",
      fieldsFn = () => {
        computeScalarInputFieldsForUpdate(model) ++ computeRelationalInputFieldsForUpdate(model, omitRelation = None)
      }
    )
  }

  protected def computeInputObjectTypeForNestedUpdate(model: Model, omitRelation: Relation): Option[InputObjectType[Any]] = {
    val field           = omitRelation.getField_!(project, model)
    val updateDataInput = computeInputObjectTypeForNestedUpdateData(model, omitRelation)

    computeInputObjectTypeForWhere(model).map { whereArg =>
      InputObjectType[Any](
        name = s"${model.name}UpdateWithout${field.name.capitalize}Input",
        fieldsFn = () => {
          List(
            InputField[Any]("where", whereArg),
            InputField[Any]("data", updateDataInput)
          )
        }
      )
    }
  }

  protected def computeInputObjectTypeForNestedUpdateData(model: Model, omitRelation: Relation): InputObjectType[Any] = {
    val field = omitRelation.getField_!(project, model)
    InputObjectType[Any](
      name = s"${model.name}UpdateWithout${field.name.capitalize}DataInput",
      fieldsFn = () => {
        computeScalarInputFieldsForUpdate(model) ++ computeRelationalInputFieldsForUpdate(model, omitRelation = Some(omitRelation))
      }
    )
  }

  protected def computeInputObjectTypeForNestedUpsert(model: Model, omitRelation: Relation): Option[InputObjectType[Any]] = {
    val field = omitRelation.getField_!(project, model)

    computeInputObjectTypeForWhere(model).map { whereArg =>
      InputObjectType[Any](
        name = s"${model.name}UpsertWithout${field.name.capitalize}Input",
        fieldsFn = () => {
          List(
            InputField[Any]("where", whereArg),
            InputField[Any]("update", computeInputObjectTypeForNestedUpdateData(model, omitRelation)),
            InputField[Any]("create", computeInputObjectTypeForCreate(model, Some(omitRelation)))
          )
        }
      )
    }
  }

  protected def computeInputObjectTypeForWhere(model: Model): Option[InputObjectType[Any]] = {
    val uniqueFields = model.fields.filter(f => f.isUnique && f.isVisible)
    if (uniqueFields.isEmpty) {
      None
    } else {
      Some(
        InputObjectType[Any](
          name = s"${model.name}WhereUniqueInput",
          fieldsFn = () => {

            uniqueFields.map { field =>
              InputField(name = field.name, fieldType = SchemaBuilderUtils.mapToOptionalInputType(field))
            }
          }
        ))
    }
  }

  private def computeScalarInputFieldsForCreate(model: Model): List[InputField[Any]] = {
    val filteredModel = model.filterFields(_.isWritable)
    computeScalarInputFields(filteredModel, FieldToInputTypeMapper.mapForCreateCase)
  }

  private def computeScalarInputFieldsForUpdate(model: Model): List[InputField[Any]] = {
    val filteredModel = model.filterFields(f => f.isWritable)
    computeScalarInputFields(filteredModel, SchemaBuilderUtils.mapToOptionalInputType)
  }

  private def computeScalarInputFields(model: Model, mapToInputType: Field => InputType[Any]): List[InputField[Any]] = {
    model.scalarFields.map { field =>
      InputField(field.name, mapToInputType(field))
    }
  }

  private def computeRelationalInputFieldsForUpdate(model: Model, omitRelation: Option[Relation]): List[InputField[Any]] = {
    model.relationFields.flatMap { field =>
      val subModel              = field.relatedModel_!(project)
      val relatedField          = field.relatedFieldEager(project)
      val relationMustBeOmitted = omitRelation.exists(rel => field.isRelationWithId(rel.id))

      val inputObjectTypeName = if (field.isList) {
        s"${subModel.name}UpdateManyWithout${relatedField.name.capitalize}Input"
      } else {
        s"${subModel.name}UpdateOneWithout${relatedField.name.capitalize}Input"
      }

      if (relationMustBeOmitted) {
        None
      } else {
        val inputObjectType = InputObjectType[Any](
          name = inputObjectTypeName,
          fieldsFn = () =>
            List(nestedCreateInputField(field)) ++
              nestedConnectInputField(field) ++
              nestedDisconnectInputField(field) ++
              nestedDeleteInputField(field) ++
              nestedUpdateInputField(field) ++
              nestedUpsertInputField(field)
        )
        Some(InputField[Any](field.name, OptionInputType(inputObjectType)))
      }
    }
  }

  private def computeRelationalInputFieldsForCreate(model: Model, omitRelation: Option[Relation]): List[InputField[Any]] = {
    model.relationFields.flatMap { field =>
      val subModel              = field.relatedModel_!(project)
      val relatedField          = field.relatedFieldEager(project)
      val relationMustBeOmitted = omitRelation.exists(rel => field.isRelationWithId(rel.id))

      val inputObjectTypeName = if (field.isList) {
        s"${subModel.name}CreateManyWithout${relatedField.name.capitalize}Input"
      } else {
        s"${subModel.name}CreateOneWithout${relatedField.name.capitalize}Input"
      }

      if (relationMustBeOmitted) {
        None
      } else {
        val inputObjectType = InputObjectType[Any](
          name = inputObjectTypeName,
          fieldsFn = () => List(nestedCreateInputField(field)) ++ nestedConnectInputField(field)
        )
        Some(InputField[Any](field.name, OptionInputType(inputObjectType)))
      }
    }
  }

  def nestedUpdateInputField(field: Field): Option[InputField[Any]] = {
    val subModel = field.relatedModel_!(project)
    val relation = field.relation.get
    val inputType = if (field.isList) {
      computeInputObjectTypeForNestedUpdate(subModel, omitRelation = relation).map(x => OptionInputType(ListInputType(x)))
    } else {
      computeInputObjectTypeForNestedUpdate(subModel, omitRelation = relation).map(x => OptionInputType(x))
    }

    inputType.map(x => InputField[Any]("update", x))
  }

  def nestedCreateInputField(field: Field): InputField[Any] = {
    val subModel = field.relatedModel_!(project)
    val relation = field.relation.get
    val inputType = if (field.isList) {
      OptionInputType(ListInputType(inputObjectTypeForCreate(subModel, Some(relation))))
    } else {
      OptionInputType(inputObjectTypeForCreate(subModel, Some(relation)))
    }

    InputField[Any]("create", inputType)
  }

  def nestedUpsertInputField(field: Field): Option[InputField[Any]] = {
    val subModel = field.relatedModel_!(project)
    val relation = field.relation.get
    val inputType = if (field.isList) {
      computeInputObjectTypeForNestedUpsert(subModel, relation).map(x => OptionInputType(ListInputType(x)))
    } else {
      computeInputObjectTypeForNestedUpsert(subModel, relation).map(x => OptionInputType(x))
    }

    inputType.map(x => InputField[Any]("upsert", x))
  }

  def nestedConnectInputField(field: Field): Option[InputField[Any]]    = whereInputField(field, name = "connect")
  def nestedDisconnectInputField(field: Field): Option[InputField[Any]] = whereInputField(field, name = "disconnect")
  def nestedDeleteInputField(field: Field): Option[InputField[Any]]     = whereInputField(field, name = "delete")

  def whereInputField(field: Field, name: String): Option[InputField[Any]] = {
    val subModel = field.relatedModel_!(project)

    inputObjectTypeForWhere(subModel).map { inputObjectType =>
      val inputType = if (field.isList) {
        OptionInputType(ListInputType(inputObjectType))
      } else {
        OptionInputType(inputObjectType)
      }

      InputField[Any](name, inputType)
    }
  }
}

object FieldToInputTypeMapper {
  def mapForCreateCase(field: Field): InputType[Any] = field.isRequired && field.defaultValue.isEmpty match {
    case true  => SchemaBuilderUtils.mapToRequiredInputType(field)
    case false => SchemaBuilderUtils.mapToOptionalInputType(field)
  }
}