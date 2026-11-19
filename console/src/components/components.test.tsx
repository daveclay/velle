import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { VelleRequestError, type VelleClient } from '../api/VelleClient';
import { testModel } from '../model/testModel';
import { ActForm } from './ActForm';
import { RecordView } from './RecordView';
import { ScheduleView } from './ScheduleView';
import { ShapeTable, type TableQuery } from './ShapeTable';

interface SentRequest {
  document: string;
  variables: Record<string, unknown>;
}

/** A client that answers each request from `answer` and keeps what it was sent. */
function fakeClient(answer: (request: SentRequest) => unknown) {
  const sent: SentRequest[] = [];
  const client: VelleClient = {
    describeModel: async () => testModel,
    request: async <Data,>(document: string, variables: Record<string, unknown> = {}) => {
      sent.push({ document, variables });
      return answer({ document, variables }) as Data;
    },
  };
  return { client, sent };
}

const recordHref = (shapeName: string, key: string | number) => `#record/${shapeName}/${key}`;

const firstInvoice = {
  shape: 'Invoice', key: '1', customer: { shape: 'Customer', key: '7' }, payer: null,
  due: '2026-04-01', paid: false, isOverdueInvoice: true,
};
const secondInvoice = { ...firstInvoice, key: '2', paid: true, isOverdueInvoice: false };

function invoicePages({ document, variables }: SentRequest) {
  if (document.includes('invoiceCount')) return { count: 2 };
  if (variables.after === 'cursor-1') return { page: { items: [secondInvoice], next: null } };
  return { page: { items: [firstInvoice], next: 'cursor-1' } };
}

afterEach(cleanup);

describe('a shape\'s table', () => {
  it('shows the first page ordered by the first orderable member, with the count, and reads more by the cursor', async () => {
    const { client, sent } = fakeClient(invoicePages);
    render(<ShapeTable model={testModel} client={client} shapeName="Invoice" recordHref={recordHref} />);

    expect((await screen.findByText('Invoice#1')).getAttribute('href')).toBe('#record/Invoice/1');
    expect(screen.getByText('Customer#7').getAttribute('href')).toBe('#record/Customer/7');
    expect(screen.getByText('OverdueInvoice')).toBeTruthy();
    expect(screen.getByText('no')).toBeTruthy();
    expect(await screen.findByText('2 in all')).toBeTruthy();
    expect(sent[0].variables).toEqual({ where: null, orderBy: ['due'], first: 25, after: null });

    await userEvent.click(screen.getByText('More'));
    expect(await screen.findByText('Invoice#2')).toBeTruthy();
    expect(screen.getByText('2 shown')).toBeTruthy();
    expect(screen.queryByText('More')).toBeNull();
  });

  it('runs the query the developer typed and tells whoever placed it', async () => {
    const { client, sent } = fakeClient(invoicePages);
    const queries: TableQuery[] = [];
    render(<ShapeTable model={testModel} client={client} shapeName="Invoice" recordHref={recordHref} onQuery={query => queries.push(query)} />);
    await screen.findByText('Invoice#1');

    await userEvent.type(screen.getByLabelText('where'), 'paid');
    await userEvent.click(screen.getByText('Query'));

    await waitFor(() => expect(sent.some(request => request.variables.where === 'paid' && request.document.includes('invoices('))).toBe(true));
    expect(queries).toEqual([{ where: 'paid', orderBy: 'due' }]);
  });

  it('shows an inverse collection as its count and says what the API refused', async () => {
    const customer = { shape: 'Customer', key: '7', name: 'Ada', key_: null, invoices: [{ shape: 'Invoice', key: '1' }, { shape: 'Invoice', key: '2' }] };
    const { client } = fakeClient(() => ({ page: { items: [customer], next: null }, count: 1 }));
    render(<ShapeTable model={testModel} client={client} shapeName="Customer" recordHref={recordHref} />);
    expect((await screen.findByText('2')).className).toBe('numeric');
    cleanup();

    const refusing = fakeClient(() => { throw new VelleRequestError('a query names its order'); });
    render(<ShapeTable model={testModel} client={refusing.client} shapeName="Company" recordHref={recordHref} />);
    expect(await screen.findByText('a query names its order')).toBeTruthy();
  });
});

describe('a record\'s page', () => {
  it('shows every member, a reference as a link, the flags set, and the refinements it reads as', async () => {
    const { client, sent } = fakeClient(() => ({ record: firstInvoice }));
    render(<RecordView model={testModel} client={client} shapeName="Invoice" recordKey="1" recordHref={recordHref} />);

    expect((await screen.findByText('Customer#7')).getAttribute('href')).toBe('#record/Customer/7');
    expect(screen.getByText('2026-04-01')).toBeTruthy();
    expect(screen.getByText('boolean · derived')).toBeTruthy();
    expect(screen.getAllByText('OverdueInvoice').map(element => element.getAttribute('href'))).toContain('#record/OverdueInvoice/1');
    expect(sent[0].variables).toEqual({ key: '1' });
  });

  it('says so when there is no such record', async () => {
    const { client } = fakeClient(() => ({ record: null }));
    render(<RecordView model={testModel} client={client} shapeName="Invoice" recordKey="99" recordHref={recordHref} />);
    expect(await screen.findByText(/There is no Invoice with the key 99/)).toBeTruthy();
  });
});

describe('an act\'s form', () => {
  it('commits what was typed and links the record the act became', async () => {
    const { client, sent } = fakeClient(() => ({ outcome: { accepted: true, ref: { shape: 'BillCustomer', key: '4' }, reason: null, rejection: null } }));
    render(<ActForm model={testModel} client={client} actName="BillCustomer" recordHref={recordHref} />);

    await userEvent.type(screen.getByLabelText('customer *'), '7');
    await userEvent.type(screen.getByLabelText('due *'), '2026-04-01');
    await userEvent.click(screen.getByText('Commit'));

    expect((await screen.findByText('BillCustomer#4')).getAttribute('href')).toBe('#record/BillCustomer/4');
    expect(sent[0].document).toContain('billCustomer(input: $input)');
    expect(sent[0].variables).toEqual({ input: { customer: '7', due: '2026-04-01' } });
  });

  it('shows a refusal with the rejection\'s message, and a transient act as accepted with no record', async () => {
    const refusing = fakeClient(() => ({ outcome: { accepted: false, ref: null, reason: 'never', rejection: { message: 'a customer has a name' } } }));
    render(<ActForm model={testModel} client={refusing.client} actName="SignUp" recordHref={recordHref} />);
    await userEvent.click(screen.getByText('Commit'));
    expect(await screen.findByText('Refused: a customer has a name')).toBeTruthy();
    cleanup();

    const accepting = fakeClient(() => ({ outcome: { accepted: true, ref: null, reason: null, rejection: null } }));
    render(<ActForm model={testModel} client={accepting.client} actName="SignUp" recordHref={recordHref} />);
    await userEvent.click(screen.getByText('Commit'));
    expect(await screen.findByText('Accepted (no record: a transient act)')).toBeTruthy();
  });

  it('does not commit JSON that does not parse', async () => {
    const { client, sent } = fakeClient(() => ({}));
    render(<ActForm model={testModel} client={client} actName="BillCustomer" recordHref={recordHref} />);
    await userEvent.type(screen.getByLabelText('lineItems'), '[[{{');
    await userEvent.click(screen.getByText('Commit'));
    expect(await screen.findByText(/The JSON in a field does not parse/)).toBeTruthy();
    expect(sent).toEqual([]);
  });
});

describe('a schedule\'s page', () => {
  it('says when the schedule last ran and offers no tick when ticks are not exposed', async () => {
    const { client } = fakeClient(() => ({ ticks: [{ schedule: 'Weekly', at: null }] }));
    render(<ScheduleView client={client} schedule="Weekly" ticksExposed={false} />);
    expect(await screen.findByText('Has not run yet.')).toBeTruthy();
    expect(screen.queryByText('Tick Weekly')).toBeNull();
  });

  it('ticks, shows what was rejected, and reads the latest run again', async () => {
    let ticked = false;
    const { client } = fakeClient(({ document }) => {
      if (document.includes('mutation')) {
        ticked = true;
        return { tick: { at: '2026-01-01T09:00:00Z', rejections: [{ message: 'an invoice is never negative' }] } };
      }
      return { ticks: [{ schedule: 'Weekly', at: ticked ? '2026-01-01T09:00:00Z' : null }] };
    });
    render(<ScheduleView client={client} schedule="Weekly" ticksExposed={true} />);
    await screen.findByText('Has not run yet.');
    await userEvent.click(screen.getByText('Tick Weekly'));
    expect(await screen.findByText(/1 firing\(s\) rejected:\s+an invoice is never negative/)).toBeTruthy();
    expect(await screen.findByText('Last ran at 2026-01-01T09:00:00Z.')).toBeTruthy();
  });
});
