{{/*
Expand the chart name.
*/}}
{{- define "healthcare.name" -}}
{{- .Chart.Name }}
{{- end }}

{{/*
Common labels added to every resource so you can select/filter by chart.
*/}}
{{- define "healthcare.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
The namespace to deploy into — taken from values.namespace.
*/}}
{{- define "healthcare.namespace" -}}
{{ .Values.namespace }}
{{- end }}
