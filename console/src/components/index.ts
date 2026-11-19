// The components another React application may place: each is given the model description, a
// client, and where a record's page is, and takes nothing from the console around it.
export { ShapeTable, type ShapeTableProps, type TableQuery } from './ShapeTable';
export { RecordView, type RecordViewProps } from './RecordView';
export { ActForm, type ActFormProps } from './ActForm';
export { ScheduleView, type ScheduleViewProps } from './ScheduleView';
export { ValueView, ReferenceLink, FlagChips, type RecordHref } from './ValueView';
export { httpClient, errorText, VelleRequestError, type VelleClient, type HttpClientOptions } from '../api/VelleClient';
export type * from '../model/description';
