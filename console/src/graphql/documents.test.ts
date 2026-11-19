import { describe, expect, it } from 'vitest';
import { testModel } from '../model/testModel';
import { actDocument, countDocument, fieldNameOf, memberValue, pageDocument, recordDocument } from './documents';

const squeezed = (document: string) => document.replace(/\s+/g, ' ').trim();

describe('the documents the console sends', () => {
  it('asks for a page of a shape with every member, a reference as its shape and key, and every flag', () => {
    expect(squeezed(pageDocument(testModel, 'Invoice'))).toBe(
      'query Page($where: String, $orderBy: [String!]!, $first: Int, $after: String) { '
      + 'page: invoices(where: $where, orderBy: $orderBy, first: $first, after: $after) { '
      + 'items { shape key customer { shape key } '
      + 'payer { ... on Company { shape key } ... on Customer { shape key } } '
      + 'due paid isOverdueInvoice } next } }');
  });

  it('asks for a member named key under the name the schema gives it', () => {
    expect(squeezed(recordDocument(testModel, 'Customer'))).toBe(
      'query Record($key: ID!) { record: customer(key: $key) { shape key name key_ invoices { shape key } } }');
    const keyMember = testModel.shapes[0].members[1];
    expect(fieldNameOf(keyMember)).toBe('key_');
    expect(memberValue({ shape: 'Customer', key: '1', key_: 'C-1' }, keyMember)).toBe('C-1');
  });

  it('asks for a refinement over several shapes shape by shape, with the members each shape has', () => {
    expect(squeezed(pageDocument(testModel, 'Payer'))).toContain(
      'items { ... on Company { shape key name } ... on Customer { shape key name invoices { shape key } } } next');
  });

  it('counts with the same predicate', () => {
    expect(squeezed(countDocument('OverdueInvoice'))).toBe(
      'query Count($where: String) { count: overdueInvoiceCount(where: $where) }');
  });

  it('commits an act through its own mutation and input type', () => {
    expect(squeezed(actDocument('SignUp'))).toBe(
      'mutation Commit($input: SignUpInput!) { outcome: signUp(input: $input) { '
      + 'accepted ref { shape key } reason rejection { message } } }');
  });
});
