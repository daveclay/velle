import { shapeNamed, type ActDescription, type FieldDescription, type ModelDescription } from '../model/description';

// What the developer typed into an act's form, as the input the act's GraphQL mutation takes
// (apis/graphql, VelleGraphQL.kt, "input types").

const NUMERIC_TYPES = ['integer', 'long', 'decimal', 'double'];

/** One typed value, as the JSON value the field's type takes. */
function scalarFrom(typeName: string, text: string): unknown {
  if (NUMERIC_TYPES.includes(typeName)) return Number(text);
  if (typeName === 'boolean') return text === 'true';
  // a datetime-local input gives the browser's local time; the API takes an instant
  if (typeName === 'DateTime' && !text.endsWith('Z')) return new Date(text).toISOString();
  return text;
}

/** A reference as typed: JSON as it stands, Shape#key as the pair, anything else as a key. */
function referenceObjectFrom(text: string): Record<string, unknown> {
  if (text.startsWith('{')) return JSON.parse(text);
  const shapeAndKey = text.match(/^(\w+)#(.+)$/);
  return shapeAndKey ? { shape: shapeAndKey[1], key: shapeAndKey[2] } : { key: text };
}

/**
 * The schema takes a reference as an object when it may point at a record created in the same act
 * or when its type is a refinement, and as a bare key otherwise.
 */
function takesReferenceObject(model: ModelDescription, field: FieldDescription): boolean {
  return Boolean(field.mayBeNew) || shapeNamed(model, field.type)?.kind === 'refinement';
}

function referenceFrom(model: ModelDescription, field: FieldDescription, text: string): unknown {
  const referenceObject = referenceObjectFrom(text);
  return takesReferenceObject(model, field) ? referenceObject : referenceObject.key;
}

function commaSeparated(text: string): string[] {
  return text.split(',').map(piece => piece.trim());
}

/** A field that may carry a new record: the JSON typed, or the key of a record that exists. */
function existingOrNewFrom(text: string): unknown {
  return text.startsWith('{') ? JSON.parse(text) : { existing: referenceObjectFrom(text) };
}

function fieldValueFrom(model: ModelDescription, field: FieldDescription, text: string): unknown {
  if (field.newValue) return existingOrNewFrom(text);
  if (field.kind === 'reference') return referenceFrom(model, field, text);
  if (field.kind === 'references') return commaSeparated(text).map(piece => referenceFrom(model, field, piece));
  if (field.kind === 'values') return commaSeparated(text).map(piece => scalarFrom(field.type, piece));
  return scalarFrom(field.type, text);
}

/**
 * The mutation's input from the text typed for each field and each part, by name. A field left
 * empty is left out. Throws when JSON typed in a field does not parse.
 */
export function actInput(model: ModelDescription, act: ActDescription, typedByName: Record<string, string>): Record<string, unknown> {
  const input: Record<string, unknown> = {};
  for (const field of act.fields) {
    const text = (typedByName[field.name] ?? '').trim();
    if (text !== '') input[field.name] = fieldValueFrom(model, field, text);
  }
  for (const part of act.parts ?? []) {
    const text = (typedByName[part.field] ?? '').trim();
    if (text !== '') input[part.field] = JSON.parse(text);
  }
  return input;
}
