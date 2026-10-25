import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, CircleCheck, Download, FileUp } from 'lucide-react';
import { Alert, Button, Card, SelectField } from '@/components/ui';
import { useAdminExams } from '@/features/admin/catalog/api';
import { safeReturnTo } from '@/features/admin/setup/returnTo';
import { ApiError } from '@/lib/api/problem';
import { getAccessToken } from '@/lib/auth/session';
import { confirmImport, questionKeys, uploadImport, type Bank, type ImportReport } from './api';

/** Bulk import from the CSV or XLSX template (FR-QB-08): check every row, then confirm to create drafts. */
export function ImportPage() {
  const queryClient = useQueryClient();
  const exams = useAdminExams({ q: '', status: '', categoryId: '', page: 0 });
  const [params] = useSearchParams();
  const returnTo = safeReturnTo(params.get('returnTo'));
  const [examId, setExamId] = useState(params.get('examId') ?? '');
  const [bank, setBank] = useState<Bank>('MAIN');
  const [file, setFile] = useState<File | null>(null);
  const [report, setReport] = useState<ImportReport | null>(null);

  const upload = useMutation({
    mutationFn: () => uploadImport(file as File, examId, bank),
    onSuccess: setReport,
  });
  const confirm = useMutation({
    mutationFn: () => confirmImport((report as ImportReport).importId),
    onSuccess: (r) => {
      setReport(r);
      void queryClient.invalidateQueries({ queryKey: questionKeys.all });
    },
  });

  const download = async (format: 'csv' | 'xlsx') => {
    const token = getAccessToken();
    const response = await fetch(`/api/admin/catalog/question-imports/template?format=${format}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!response.ok) return;
    const url = URL.createObjectURL(await response.blob());
    const a = document.createElement('a');
    a.href = url;
    a.download = `question-import-template.${format}`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const uploadError = upload.error instanceof ApiError ? upload.error.message : upload.isError ? 'Please try again.' : null;
  const confirmError = confirm.error instanceof ApiError ? confirm.error.message : confirm.isError ? 'Please try again.' : null;
  const confirmed = report?.status === 'CONFIRMED';

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-2">
        <Link to={returnTo ?? '/admin/questions'} className="inline-flex items-center gap-1.5 text-[14.5px] font-semibold">
          <ArrowLeft aria-hidden="true" className="size-4" /> {returnTo ? 'Back to exam setup' : 'All questions'}
        </Link>
        <h1 className="text-2xl font-extrabold tracking-tight">Import questions</h1>
        <p className="text-muted">
          Fill in the template, upload it, and check the report. Nothing is saved until you confirm; imported questions
          start as drafts.
        </p>
      </div>

      <Card className="flex flex-col gap-4 p-6">
        <h2 className="text-lg font-bold">1. Get the template</h2>
        <p className="text-[14.5px] text-muted">
          One row per question. Subject, module and topic must match the content linked to the exam. Separate key points,
          definitions and tags with “|”; write definitions as “term: meaning”; give correct answers as letters, such as “B”
          or “A, C”.
        </p>
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" size="sm" onClick={() => void download('xlsx')}>
            <Download aria-hidden="true" className="size-4" /> Excel template (.xlsx)
          </Button>
          <Button variant="secondary" size="sm" onClick={() => void download('csv')}>
            <Download aria-hidden="true" className="size-4" /> CSV template (UTF-8)
          </Button>
        </div>
      </Card>

      <Card className="flex flex-col gap-4 p-6">
        <h2 className="text-lg font-bold">2. Upload and check</h2>
        <form
          noValidate
          className="grid gap-4 sm:grid-cols-2"
          onSubmit={(e) => {
            e.preventDefault();
            setReport(null);
            confirm.reset();
            upload.mutate();
          }}
        >
          <SelectField label="Exam" value={examId} onChange={(e) => setExamId(e.target.value)}>
            <option value="">Choose the exam the topics belong to</option>
            {exams.data?.items.map((x) => (
              <option key={x.id} value={x.id}>
                {x.title}
              </option>
            ))}
          </SelectField>
          <SelectField label="Bank" value={bank} onChange={(e) => setBank(e.target.value as Bank)}>
            <option value="MAIN">Main bank</option>
            <option value="TRIAL">Trial bank</option>
          </SelectField>
          <div className="flex flex-col gap-1.5 sm:col-span-2">
            <label htmlFor="import-file" className="text-sm font-semibold">
              File (.xlsx or .csv, up to 5 MB and 2,000 questions)
            </label>
            <input
              id="import-file"
              type="file"
              accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              className="text-[15px] file:mr-3 file:rounded-md file:border file:border-border-strong file:bg-surface file:px-3 file:py-2 file:font-semibold"
            />
          </div>
          <div className="sm:col-span-2">
            <Button type="submit" disabled={!file || !examId} loading={upload.isPending}>
              <FileUp aria-hidden="true" className="size-4" /> Check file
            </Button>
          </div>
        </form>
        {uploadError && (
          <Alert tone="error" title="The file couldn’t be checked">
            <p>{uploadError}</p>
          </Alert>
        )}
      </Card>

      {report && (
        <Card className="flex flex-col gap-4 p-6">
          <h2 className="text-lg font-bold">3. {confirmed ? 'Done' : 'Review and confirm'}</h2>
          <dl className="grid grid-cols-3 gap-3 text-center">
            {[
              ['Rows', report.totalRows],
              ['Ready to import', report.validRows],
              ['Rows with problems', report.totalRows - report.validRows],
            ].map(([label, value]) => (
              <div key={label} className="rounded-md bg-surface-2 px-3 py-3">
                <dt className="text-[13px] text-muted">{label}</dt>
                <dd className="text-2xl font-extrabold tabular-nums">{value}</dd>
              </div>
            ))}
          </dl>
          {report.errors.length > 0 && (
            <div className="relative max-h-96 overflow-auto rounded-md border border-border">
              <table className="w-full border-collapse text-[14px]">
                <caption className="sr-only">Problems found, by row</caption>
                <thead className="sticky top-0 bg-[#fafbfc]">
                  <tr className="border-b border-border text-left text-[12.5px] font-bold tracking-wider text-muted uppercase">
                    <th scope="col" className="px-3 py-2">Row</th>
                    <th scope="col" className="px-3 py-2">Column</th>
                    <th scope="col" className="px-3 py-2">Problem</th>
                  </tr>
                </thead>
                <tbody>
                  {report.errors.map((e, i) => (
                    <tr key={`${e.row}-${e.column}-${i}`} className="border-b border-border last:border-b-0">
                      <td className="px-3 py-2 tabular-nums">{e.row}</td>
                      <td className="px-3 py-2 font-mono text-[13px]">{e.column}</td>
                      <td className="px-3 py-2">{e.message}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {confirmed ? (
            <Alert tone="success" title={`${report.createdQuestionIds.length} draft question(s) created.`}>
              <p>
                {returnTo ? (
                  <>
                    <Link to={returnTo}>Back to the exam setup</Link> to check them and mark them ready.
                  </>
                ) : (
                  <>
                    <Link to="/admin/questions">Open the question list</Link> to check them and mark them ready.
                  </>
                )}
              </p>
            </Alert>
          ) : report.validRows > 0 ? (
            <div className="flex flex-wrap items-center gap-3">
              <Button loading={confirm.isPending} onClick={() => confirm.mutate()}>
                <CircleCheck aria-hidden="true" className="size-4" /> Import {report.validRows} question(s) as drafts
              </Button>
              {report.errors.length > 0 && <span className="text-[14px] text-muted">Rows with problems are skipped. Fix them and upload them again.</span>}
            </div>
          ) : (
            <Alert tone="warning" title="No rows can be imported yet">
              <p>Fix the problems listed and upload the file again.</p>
            </Alert>
          )}
          {confirmError && (
            <Alert tone="error" title="The import wasn’t completed">
              <p>{confirmError}</p>
            </Alert>
          )}
        </Card>
      )}
      <p className="text-[13px] text-muted">Imports wait 24 hours for confirmation. The template’s example row shows every column filled in.</p>
    </div>
  );
}
