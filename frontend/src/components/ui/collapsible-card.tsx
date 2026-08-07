"use client";

import { useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { ChevronDown, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";

interface CollapsibleCardProps {
  title: React.ReactNode;
  action?: React.ReactNode;
  defaultOpen?: boolean;
  className?: string;
  children: React.ReactNode;
}

export function CollapsibleCard({
  title,
  action,
  defaultOpen = true,
  className,
  children,
}: CollapsibleCardProps) {
  const [open, setOpen] = useState(defaultOpen);

  return (
    <Card className={cn("md:col-span-2 lg:col-span-3", className)}>
      <CardHeader className="flex flex-row items-center justify-between gap-2">
        <Button
          variant="ghost"
          size="sm"
          className="gap-1 px-0 font-semibold leading-none tracking-tight"
          onClick={() => setOpen(!open)}
          aria-expanded={open}
        >
          {open ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
          <CardTitle>{title}</CardTitle>
        </Button>
        {action && <div className="flex items-center gap-2">{action}</div>}
      </CardHeader>
      {open && <CardContent>{children}</CardContent>}
    </Card>
  );
}
