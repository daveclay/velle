import { useEffect, useState, type FormEvent } from 'react';
import { actInput } from '../acts/actInput';
import { errorText, type VelleClient } from '../api/VelleClient';
import { actDocument } from '../graphql/documents';
import {
  actNamed, readsSeveralShapes, shapeNamed,
  type ActDescription, type FieldDescription, type ModelDescription, type PartDescription, type Reference,
} from '../model/description';
import { ReferenceLink, type RecordHref } from './ValueView';

export interface ActFormProps {
  model: ModelDescription;
  client: VelleClient;
  actName: string;
  recordHref: RecordHref;
}

/** The commit's verdict as the mutation answers it. */
interface CommitOutcome {
  accepted: boolean;
  ref: Reference | null;
  reason: string | null;
  rejection: { message: string } | null;
}

/** What the form says after a commit: the sentence, whether it was accepted, and the record it became. */
interface Verdict {
  accepted: boolean;
  text: string;
  reference?: Reference;
}

interface TypedInputProps {
  inputId: string;
  text: string;
  onText: (text: string) => void;
}

function actSubtitle(act: ActDescription): string {
  const effect = act.transient
    ? 'A transient act: it leaves no record of its own.'
    : `A durable act: the commit becomes a ${act.name} record.`;
  const actor = act.actor ? ` Committed by a ${act.actor.shape}, resolved from the request.` : '';
  return effect + actor;
}

function referencePlaceholder(model: ModelDescription, field: FieldDescription): string {
  const referencedShape = shapeNamed(model, field.type);
  return readsSeveralShapes(referencedShape)
    ? 'Shape#key, one of ' + referencedShape!.bases!.join(', ')
    : 'key of a ' + field.type;
}

/** The HTML input type and step a value's type is entered with; a type with none is entered as text. */
const INPUTS_BY_TYPE: Record<string, { type: string; step?: string }> = {
  integer: { type: 'number', step: '1' },
  long: { type: 'number', step: '1' },
  decimal: { type: 'number', step: 'any' },
  double: { type: 'number', step: 'any' },
  Date: { type: 'date' },
  DateTime: { type: 'datetime-local', step: '1' },
  time: { type: 'time', step: '1' },
};

function BooleanInput({ inputId, text, onText }: TypedInputProps) {
  return (
    <select id={inputId} value={text} onChange={event => onText(event.target.value)}>
      <option value="">—</option>
      <option value="true">true</option>
      <option value="false">false</option>
    </select>
  );
}

function placeholderFor(model: ModelDescription, field: FieldDescription): string {
  if (field.kind === 'reference') return referencePlaceholder(model, field);
  if (field.kind === 'references') return 'keys, comma-separated';
  if (field.kind === 'values') return `${field.type} values, comma-separated`;
  return field.type;
}

/** The input for one field of the act, from the field's kind and type. */
function FieldInput({ model, field, inputId, text, onText }: TypedInputProps & { model: ModelDescription; field: FieldDescription }) {
  const onChange = (event: { target: { value: string } }) => onText(event.target.value);
  if (field.newValue) {
    const placeholder = `a key, or a new ${field.type} as JSON: {"new${field.newValue[0].shape}": {...}}`;
    return <textarea id={inputId} placeholder={placeholder} value={text} onChange={onChange} />;
  }
  if (field.kind === 'value' && field.type === 'boolean') return <BooleanInput inputId={inputId} text={text} onText={onText} />;
  const typedInput = field.kind === 'value' ? INPUTS_BY_TYPE[field.type] : undefined;
  if (typedInput) return <input id={inputId} type={typedInput.type} step={typedInput.step} value={text} onChange={onChange} />;
  return <input id={inputId} placeholder={placeholderFor(model, field)} value={text} onChange={onChange} />;
}

function fieldHint(field: FieldDescription): string {
  return field.type
    + (field.kind !== 'value' ? ' · ' + field.kind : '')
    + (field.mayBeNew ? ' · may be {"new": n}' : '');
}

/** The names a new part is typed with: its fields, or one `new<Shape>` per shape the part may be. */
function partFieldNames(part: PartDescription): string[] {
  if (part.fields) return part.fields.map(partField => partField.name);
  return (part.kinds ?? []).map(kind => 'new' + kind.shape);
}

/** A part is a collection of new records committed with the act, typed as a JSON list. */
function PartInput({ part, inputId, text, onText }: TypedInputProps & { part: PartDescription }) {
  const fieldNames = partFieldNames(part);
  const placeholder = `new ${part.shape} values as JSON: [{${fieldNames.map(name => `"${name}": …`).join(', ')}}]`;
  return (
    <>
      <label htmlFor={inputId}>{part.field}</label>
      <textarea id={inputId} placeholder={placeholder} value={text} onChange={event => onText(event.target.value)} />
      <div className="hint">parts: {part.shape} · {fieldNames.join(', ')}</div>
    </>
  );
}

function verdictOf(outcome: CommitOutcome): Verdict {
  if (!outcome.accepted) {
    // a refusal is data: the rule that refused, or the reason
    return { accepted: false, text: 'Refused: ' + (outcome.rejection ? outcome.rejection.message : outcome.reason) };
  }
  if (!outcome.ref) return { accepted: true, text: 'Accepted (no record: a transient act)' };
  return { accepted: true, text: `Accepted: ${outcome.ref.shape}#${outcome.ref.key}`, reference: outcome.ref };
}

/** An act as a form: an input per field, a JSON list per part, and the commit's verdict beneath. */
export function ActForm({ model, client, actName, recordHref }: ActFormProps) {
  const act = actNamed(model, actName);
  const [typedByName, setTypedByName] = useState<Record<string, string>>({});
  const [verdict, setVerdict] = useState<Verdict | null>(null);

  // another act starts with an empty form
  useEffect(() => {
    setTypedByName({});
    setVerdict(null);
  }, [actName]);

  if (!act) return null;
  const committedAct = act;

  const typed = (name: string) => typedByName[name] ?? '';
  const setTyped = (name: string) => (text: string) => setTypedByName({ ...typedByName, [name]: text });

  async function commit(event: FormEvent) {
    event.preventDefault();
    let input: Record<string, unknown>;
    try {
      input = actInput(model, committedAct, typedByName);
    } catch (parseError) {
      setVerdict({ accepted: false, text: 'The JSON in a field does not parse: ' + errorText(parseError) });
      return;
    }
    try {
      const answer = await client.request<{ outcome: CommitOutcome }>(actDocument(actName), { input });
      setVerdict(verdictOf(answer.outcome));
    } catch (thrown) {
      setVerdict({ accepted: false, text: errorText(thrown) });
    }
  }

  return (
    <>
      <h2 className="title">{actName}</h2>
      <p className="subtitle">{actSubtitle(act)}</p>
      <form className="act-form" onSubmit={commit}>
        {act.fields.map(field => {
          const inputId = 'field-' + field.name;
          return (
            <div className="form-row" key={field.name}>
              <label htmlFor={inputId}>{field.name}{field.optional ? '' : ' *'}</label>
              <FieldInput model={model} field={field} inputId={inputId} text={typed(field.name)} onText={setTyped(field.name)} />
              <div className="hint">{fieldHint(field)}</div>
            </div>
          );
        })}
        {(act.parts ?? []).map(part => (
          <div className="form-row" key={part.field}>
            <PartInput part={part} inputId={'part-' + part.field} text={typed(part.field)} onText={setTyped(part.field)} />
          </div>
        ))}
        <span></span><div><button className="primary">Commit</button></div>
      </form>
      {verdict && (
        <pre className={'outcome ' + (verdict.accepted ? 'ok' : 'bad')}>
          {verdict.text}
          {verdict.reference && <> · <ReferenceLink reference={verdict.reference} recordHref={recordHref} /></>}
        </pre>
      )}
    </>
  );
}
