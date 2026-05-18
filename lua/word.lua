local M = {
	apply = vim.fn.Apply,
	style = vim.fn.Style,
	suggest = vim.fn.Suggest,
}

M.setup = function(opts)
	M.config = opts
end

return M
