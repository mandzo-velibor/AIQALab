"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/button";
import { QaWorkflow } from "@/components/qa-workflow";
import Link from "next/link";

function AnalyzePageContent() {
  const searchParams = useSearchParams();
  const urlFromQuery = searchParams.get("url") || "";
  const projectIdQuery = searchParams.get("projectId");
  const projectId = projectIdQuery ? Number(projectIdQuery) : undefined;

  return (
    <div className="flex-1 space-y-6 p-6">
      <div className="flex items-center justify-between">
        <div>
          <div className="text-xs font-medium uppercase tracking-widest text-primary">
            QA Workflow
          </div>
          <h1 className="text-3xl font-bold tracking-tight">
            Analyze <span className="text-gradient">Page</span>
          </h1>
        </div>
        <Link href="/">
          <Button variant="outline">Back to Dashboard</Button>
        </Link>
      </div>

      <QaWorkflow url={urlFromQuery} projectId={projectId} />
    </div>
  );
}

export function AnalyzePage() {
  return (
    <Suspense fallback={<div className="flex-1 p-6">Loading...</div>}>
      <AnalyzePageContent />
    </Suspense>
  );
}
