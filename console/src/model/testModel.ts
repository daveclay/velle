import type { ModelDescription } from './description';

// A small model description for the tests, in the form GET <api> answers: customers, their
// invoices, a refinement of one shape, a refinement over two shapes, and three acts.
export const testModel: ModelDescription = {
  authorization: false,
  schedules: ['Weekly'],
  acts: [
    {
      name: 'SignUp',
      transient: true,
      actor: null,
      fields: [
        { name: 'name', type: 'text', kind: 'value', optional: false },
        { name: 'email', type: 'text', kind: 'value', optional: true },
      ],
      parts: [],
    },
    {
      name: 'BillCustomer',
      transient: false,
      actor: { field: 'staff', shape: 'Staff' },
      fields: [
        { name: 'customer', type: 'Customer', kind: 'reference', optional: false },
        { name: 'due', type: 'Date', kind: 'value', optional: false },
        { name: 'payer', type: 'Payer', kind: 'reference', optional: true },
        { name: 'copies', type: 'integer', kind: 'values', optional: false },
        { name: 'watchers', type: 'Customer', kind: 'references', optional: false },
        { name: 'sibling', type: 'Customer', kind: 'reference', optional: true, mayBeNew: true },
        {
          name: 'address', type: 'Address', kind: 'reference', optional: true,
          newValue: [{ shape: 'Address', fields: [{ name: 'street', type: 'text', kind: 'value', optional: false }], parts: [] }],
        },
      ],
      parts: [
        {
          field: 'lineItems', shape: 'LineItem',
          fields: [
            { name: 'description', type: 'text', kind: 'value', optional: false },
            { name: 'quantity', type: 'integer', kind: 'value', optional: false },
          ],
          parts: [],
        },
      ],
    },
  ],
  shapes: [
    {
      name: 'Customer', kind: 'shape',
      members: [
        { name: 'name', type: 'text', kind: 'stored', optional: false, orderable: true },
        { name: 'key', type: 'text', kind: 'stored', optional: true, orderable: true },
        { name: 'invoices', type: 'Invoice', kind: 'inverse', optional: false, orderable: false },
      ],
      flags: [],
    },
    {
      name: 'Invoice', kind: 'shape',
      members: [
        { name: 'customer', type: 'Customer', kind: 'stored', optional: false, orderable: false },
        { name: 'payer', type: 'Payer', kind: 'stored', optional: true, orderable: false },
        { name: 'due', type: 'Date', kind: 'stored', optional: false, orderable: true },
        { name: 'paid', type: 'boolean', kind: 'derived', optional: false, orderable: true },
      ],
      flags: ['isOverdueInvoice'],
    },
    {
      name: 'Company', kind: 'shape',
      members: [{ name: 'name', type: 'text', kind: 'stored', optional: false, orderable: true }],
      flags: [],
    },
    {
      name: 'OverdueInvoice', kind: 'refinement', bases: ['Invoice'],
      members: [
        { name: 'customer', type: 'Customer', kind: 'stored', optional: false, orderable: false },
        { name: 'due', type: 'Date', kind: 'stored', optional: false, orderable: true },
      ],
    },
    {
      name: 'Payer', kind: 'refinement', bases: ['Company', 'Customer'],
      members: [
        { name: 'name', type: 'text', kind: 'stored', optional: false, orderable: true },
        { name: 'invoices', type: 'Invoice', kind: 'inverse', optional: false, orderable: false },
      ],
    },
  ],
};
