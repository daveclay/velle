import { useEffect, useState } from 'react';
import { errorText, type VelleClient } from '../api/VelleClient';
import { memberValue, recordDocument } from '../graphql/documents';
import { refinementsHolding, shapeNamed, type MemberDescription, type ModelDescription, type VelleRecord } from '../model/description';
import { FlagChips, ValueView, type RecordHref } from './ValueView';

export interface RecordViewProps {
  model: ModelDescription;
  client: VelleClient;
  shapeName: string;
  recordKey: string;
  recordHref: RecordHref;
}

function MemberLabel({ member }: { member: MemberDescription }) {
  const typeNote = member.type + (member.kind !== 'stored' ? ' · ' + member.kind : '');
  return <>{member.name} <span className="muted member-type">{typeNote}</span></>;
}

/** The refinements the record belongs to, each a link to the record read as a member of it. */
function ReadableAs({ model, shapeName, record, recordHref }: { model: ModelDescription; shapeName: string; record: VelleRecord; recordHref: RecordHref }) {
  const refinements = refinementsHolding(model, shapeName, record);
  if (refinements.length === 0) return null;
  return (
    <p className="muted">
      Read as a member of:{' '}
      {refinements.map((refinement, position) => (
        <span key={refinement.name}>
          {position > 0 ? ', ' : ''}
          <a className="ref" href={recordHref(refinement.name, record.key)}>{refinement.name}</a>
        </span>
      ))}
    </p>
  );
}

/** One record: every member with its type and its value, a reference as a link, and the flags set. */
export function RecordView({ model, client, shapeName, recordKey, recordHref }: RecordViewProps) {
  const [record, setRecord] = useState<VelleRecord | null>(null);
  const [refusal, setRefusal] = useState<string | null>(null);

  useEffect(() => {
    let abandoned = false;
    setRecord(null);
    setRefusal(null);
    Promise.resolve()
      .then(() => client.request<{ record: VelleRecord | null }>(recordDocument(model, shapeName), { key: recordKey }))
      .then(answer => {
        if (abandoned) return;
        if (answer.record) setRecord(answer.record);
        else setRefusal(`There is no ${shapeName} with the key ${recordKey}, or it is not shown to the caller.`);
      })
      .catch(thrown => { if (!abandoned) setRefusal(errorText(thrown)); });
    return () => { abandoned = true; };
  }, [model, client, shapeName, recordKey]);

  const shape = shapeNamed(model, shapeName);
  const title = <h2 className="title">{shapeName} #{recordKey}</h2>;
  if (refusal !== null) return <>{title}<pre className="outcome bad">{refusal}</pre></>;
  if (record === null || !shape) return title;

  const flagsSet = (shape.flags ?? []).filter(flagName => record[flagName] === true);
  return (
    <>
      {title}
      <p className="subtitle">{flagsSet.length > 0 ? <FlagChips flagNames={flagsSet} /> : 'A record.'}</p>
      <dl>
        {shape.members.map(member => (
          <div className="member" key={member.name}>
            <dt><MemberLabel member={member} /></dt>
            <dd><ValueView value={memberValue(record, member)} recordHref={recordHref} /></dd>
          </div>
        ))}
      </dl>
      <ReadableAs model={model} shapeName={shapeName} record={record} recordHref={recordHref} />
    </>
  );
}
